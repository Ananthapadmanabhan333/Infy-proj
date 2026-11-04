package com.nexus360.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.audit.AuditService;
import com.nexus360.observability.OtlpIngestService.Signal;
import com.nexus360.observability.TelemetryModel.IngestResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OTLP/HTTP receiver, JSON encoding only ({@code Content-Type: application/json}, optionally {@code Content-Encoding: gzip}):
 * {@code POST /api/otel/v1/metrics|logs|traces} with {@code Authorization: Bearer <telemetry source token>}.
 *
 * <ul>
 *   <li>{@code 200} — {@code {}} when everything was accepted, else {@code {"partialSuccess": {"rejectedDataPoints"|"rejectedLogRecords"|
 *       "rejectedSpans": n, "errorMessage": "..."}}} (OTLP partial success).</li>
 *   <li>{@code 401} — missing, unknown or inactive token (audited {@code OTLP_REJECTED}); {@code 415} — protobuf or any other content type
 *       (binary protobuf is not supported); {@code 413} — body (or decompressed body) above {@code app.observability.max-request-bytes}
 *       (2 MB); {@code 400} — not an OTLP JSON object; {@code 429} — the inbound rate-limit bucket.</li>
 * </ul>
 * Error bodies follow OTLP ({@code google.rpc.Status} as JSON: {@code {"code": <grpc code>, "message": "..."}}).
 */
@Tag(name = "Observability — OTLP ingestion")
@RestController
@RequestMapping("/api/otel/v1")
@RequiredArgsConstructor
public class OtlpController {

    private final TelemetrySourceService sources;
    private final OtlpIngestService ingest;
    private final AuditService audit;
    private final ObjectMapper json;
    @Value("${app.observability.max-request-bytes:2097152}")
    private int maxBytes = 2 * 1024 * 1024;

    @Operation(summary = "OTLP/HTTP JSON metrics export (ExportMetricsServiceRequest)")
    @PostMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics(HttpServletRequest request) {
        return handle(request, Signal.METRICS, "rejectedDataPoints");
    }

    @Operation(summary = "OTLP/HTTP JSON logs export (ExportLogsServiceRequest)")
    @PostMapping("/logs")
    public ResponseEntity<Map<String, Object>> logs(HttpServletRequest request) {
        return handle(request, Signal.LOGS, "rejectedLogRecords");
    }

    @Operation(summary = "OTLP/HTTP JSON traces export (ExportTraceServiceRequest)")
    @PostMapping("/traces")
    public ResponseEntity<Map<String, Object>> traces(HttpServletRequest request) {
        return handle(request, Signal.TRACES, "rejectedSpans");
    }

    private ResponseEntity<Map<String, Object>> handle(HttpServletRequest request, Signal signal, String rejectedField) {
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7).trim() : null;
        Optional<TelemetrySourceService.OtlpSource> source = sources.authenticateOtlp(token);
        if (source.isEmpty()) {
            audit.recordAs(null, "otlp", "OTLP_REJECTED", "TELEMETRY_SOURCE", null, Map.of("signal", signal.name(), "reason",
                    token == null ? "missing bearer token" : "unknown or inactive token"), AuditService.DENIED);
            return status(HttpStatus.UNAUTHORIZED, 16, "Unauthorized");
        }
        String contentType = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
        if (contentType.contains("protobuf")) {
            return status(HttpStatus.UNSUPPORTED_MEDIA_TYPE, 3, "OTLP/HTTP binary protobuf is not supported; send OTLP JSON (Content-Type: application/json)");
        }
        if (!contentType.startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            return status(HttpStatus.UNSUPPORTED_MEDIA_TYPE, 3, "Content-Type must be application/json (OTLP/HTTP JSON encoding)");
        }
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            return status(HttpStatus.PAYLOAD_TOO_LARGE, 8, "Request body larger than " + maxBytes + " bytes");
        }
        byte[] body;
        try {
            String encoding = request.getHeader("Content-Encoding");
            InputStream in = request.getInputStream();
            if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                in = new GZIPInputStream(in);
            } else if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.trim())) {
                return status(HttpStatus.UNSUPPORTED_MEDIA_TYPE, 3, "Content-Encoding must be gzip or identity");
            }
            body = readLimited(in, maxBytes);
        } catch (IOException e) {
            return status(HttpStatus.BAD_REQUEST, 3, "The request body could not be read (invalid gzip data?)");
        }
        if (body == null) {
            return status(HttpStatus.PAYLOAD_TOO_LARGE, 8, "Request body larger than " + maxBytes + " bytes");
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JsonProcessingException e) {
            return status(HttpStatus.BAD_REQUEST, 3, "The request body is not valid JSON");
        } catch (IOException e) {
            return status(HttpStatus.BAD_REQUEST, 3, "The request body could not be read");
        }
        IngestResult result;
        try {
            result = ingest.ingest(signal, root, source.get().id(), false);
        } catch (IllegalArgumentException e) {
            return status(HttpStatus.BAD_REQUEST, 3, e.getMessage());
        }
        sources.markDelivery(source.get().id(), result.accepted());
        Map<String, Object> response = new LinkedHashMap<>();
        if (result.rejected() > 0) {
            Map<String, Object> partial = new LinkedHashMap<>();
            partial.put(rejectedField, result.rejected());
            partial.put("errorMessage", result.errorMessage());
            response.put("partialSuccess", partial);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    /** Reads at most {@code max} bytes; {@code null} when the stream is longer. */
    static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > max) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static ResponseEntity<Map<String, Object>> status(HttpStatus status, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}

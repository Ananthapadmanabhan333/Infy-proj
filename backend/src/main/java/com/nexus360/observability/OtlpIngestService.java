package com.nexus360.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.admin.SettingsService;
import com.nexus360.observability.TelemetryModel.IngestResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * OTLP/HTTP JSON ingestion shared by the {@code /api/otel/v1/*} endpoints and the simulation scenarios (which generate OTLP JSON and
 * send it through exactly this path with {@code simulated = true}). Timestamps older than the signal's retention or more than ten
 * minutes in the future are rejected (partial success).
 */
@Service
@RequiredArgsConstructor
public class OtlpIngestService {

    public enum Signal { METRICS, LOGS, TRACES }

    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(10);

    private final TelemetryWriter writer;
    private final SettingsService settings;
    private final Clock clock;
    @Value("${app.observability.max-items-per-request:10000}")
    private int maxItems = 10_000;

    public int maxItems() {
        return maxItems;
    }

    /** Parses and stores one OTLP JSON export request; {@code IllegalArgumentException} when the body is not an OTLP JSON object. */
    public IngestResult ingest(Signal signal, JsonNode body, Long sourceId, boolean simulated) {
        Instant now = clock.instant();
        int retentionDays = switch (signal) {
            case METRICS -> settings.getInt("observability.retention.raw_days", 7);
            case LOGS -> settings.getInt("observability.retention.logs_days", 14);
            case TRACES -> settings.getInt("observability.retention.traces_days", 7);
        };
        OtlpJsonParser parser = new OtlpJsonParser(maxItems, now.minus(Duration.ofDays(Math.max(1, retentionDays))), now.plus(MAX_FUTURE_SKEW));
        TelemetryWriter.Context ctx = new TelemetryWriter.Context(sourceId, simulated);
        return switch (signal) {
            case METRICS -> writer.writeMetrics(ctx, parser.metrics(body));
            case LOGS -> writer.writeLogs(ctx, parser.logs(body));
            case TRACES -> writer.writeSpans(ctx, parser.traces(body));
        };
    }
}

package com.nexus360.observability;

import com.nexus360.common.ApiException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pull adapter for an external metrics system: configuration validation, a request builder, a response parser and (where the
 * authentication scheme is implemented) a test call. Outbound calls are made by {@link OutboundHttp} (SSRF guard, no redirects, size
 * and time limits); credentials are added there from the decrypted source secret and never appear in a built request.
 *
 * <p>Only {@code PROMETHEUS_API} is a complete integration (client + scheduled pull). {@code GRAFANA}, {@code CLOUDWATCH},
 * {@code AZURE_MONITOR} and {@code GCP_MONITORING} are <b>prepared</b>: configuration, request builder and parser tested with fixture
 * payloads; a source stays {@code NOT_CONNECTED} until a test call succeeds against the real system.
 */
public interface TelemetryAdapter {

    TelemetrySourceKind kind();

    /** Prepared adapters are not claimed operational (no scheduled pull) until verified. */
    boolean prepared();

    /** Default API base URL when the source has none (cloud APIs), else {@code null} (base URL required). */
    default String defaultBaseUrl(Map<String, Object> config) {
        return null;
    }

    /** Validates the adapter-specific configuration ({@code 400} with the field name otherwise). */
    void validate(Map<String, Object> config);

    /** Configured queries (name = metric name stored in SA). */
    List<MetricQuery> queries(Map<String, Object> config);

    OutboundRequest buildQuery(String baseUrl, Map<String, Object> config, MetricQuery query, Instant from, Instant to, Duration step);

    /** Parses a query response into series; {@code IllegalArgumentException} for payloads that are not the expected format. */
    List<SeriesData> parse(String body, MetricQuery query);

    /** The test request, or {@code null} when this release cannot authenticate against the system (the reason is {@link #testUnsupportedReason}). */
    OutboundRequest buildTest(String baseUrl, Map<String, Object> config);

    /** Whether a test response means the system answered and accepted the credentials. */
    default boolean testSucceeded(int status, String body) {
        return status >= 200 && status < 300;
    }

    default String testUnsupportedReason() {
        return null;
    }

    // ------------------------------------------------------------------ shared types

    /** {@code name}: metric name stored in SA; {@code expression}: the system's query (PromQL, metric name, filter ...); {@code options}: extras. */
    record MetricQuery(String name, String expression, Map<String, String> options) {}

    /** A request without credentials. */
    record OutboundRequest(String method, URI uri, Map<String, String> headers, String body) {}

    record Sample(Instant timestamp, double value) {}

    /** One returned series: resource attributes (service.name, host.name, cloud.* ...), point attributes and samples. */
    record SeriesData(Map<String, String> resource, String name, Map<String, String> attributes, List<Sample> samples) {}

    // ------------------------------------------------------------------ helpers

    static String requireString(Map<String, Object> config, String key, int max) {
        Object v = config == null ? null : config.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw ApiException.invalidField("config." + key, key + " is required");
        }
        String s = String.valueOf(v).trim();
        if (s.length() > max) {
            throw ApiException.invalidField("config." + key, key + " must be at most " + max + " characters");
        }
        return s;
    }

    static String optionalString(Map<String, Object> config, String key, String fallback) {
        Object v = config == null ? null : config.get(key);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v).trim();
    }

    /** {@code config.queries}: a list of objects with the given required keys (1–20 entries). */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> queryList(Map<String, Object> config, List<String> requiredKeys) {
        Object raw = config == null ? null : config.get("queries");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw ApiException.invalidField("config.queries", "At least one query is required");
        }
        if (list.size() > 20) {
            throw ApiException.invalidField("config.queries", "At most 20 queries per source");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw ApiException.invalidField("config.queries", "Each query must be an object");
            }
            Map<String, Object> q = new LinkedHashMap<>((Map<String, Object>) m);
            for (String k : requiredKeys) {
                Object v = q.get(k);
                if (v == null || String.valueOf(v).isBlank()) {
                    throw ApiException.invalidField("config.queries", "Each query needs '" + k + "'");
                }
                if (String.valueOf(v).length() > 1000) {
                    throw ApiException.invalidField("config.queries", "'" + k + "' must be at most 1000 characters");
                }
            }
            Object name = q.get("name");
            if (name != null && !String.valueOf(name).matches("^[A-Za-z_:][A-Za-z0-9_.:\\-/]{0,199}$")) {
                throw ApiException.invalidField("config.queries", "Query names are metric names (letters, digits, '_', '.', ':', '-', '/')");
            }
            out.add(q);
        }
        return out;
    }

    static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String stripSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    static Map<String, String> stringOptions(Map<String, Object> q, String... keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) {
            Object v = q.get(k);
            if (v != null) {
                out.put(k, v instanceof Map<?, ?> || v instanceof List<?> ? TelemetryJson.write(v) : String.valueOf(v));
            }
        }
        return out;
    }
}

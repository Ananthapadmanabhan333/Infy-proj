package com.nexus360.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.common.ApiException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Prometheus HTTP API (also Thanos, Cortex, Mimir, VictoriaMetrics): {@code GET /api/v1/query_range} with PromQL, bearer or basic
 * auth. Config: {@code queries: [{name, query}]} (1–20). Each returned series becomes an SA series: label {@code service} (else
 * {@code job}) → {@code service.name}, {@code instance} host → {@code host.name}, the other labels (without {@code __name__}) are
 * point attributes. Test call: {@code GET /api/v1/status/buildinfo} must answer {@code status: success}.
 */
@Component
public class PrometheusAdapter implements TelemetryAdapter {

    @Override
    public TelemetrySourceKind kind() {
        return TelemetrySourceKind.PROMETHEUS_API;
    }

    @Override
    public boolean prepared() {
        return false;
    }

    @Override
    public void validate(Map<String, Object> config) {
        TelemetryAdapter.queryList(config, List.of("name", "query"));
    }

    @Override
    public List<MetricQuery> queries(Map<String, Object> config) {
        return TelemetryAdapter.queryList(config, List.of("name", "query")).stream()
                .map(q -> new MetricQuery(String.valueOf(q.get("name")).trim(), String.valueOf(q.get("query")).trim(), Map.of())).toList();
    }

    @Override
    public OutboundRequest buildQuery(String baseUrl, Map<String, Object> config, MetricQuery query, Instant from, Instant to, Duration step) {
        String uri = TelemetryAdapter.stripSlash(baseUrl) + "/api/v1/query_range?query=" + TelemetryAdapter.encode(query.expression()) + "&start="
                + from.getEpochSecond() + "&end=" + to.getEpochSecond() + "&step=" + Math.max(1, step.toSeconds()) + "s";
        return new OutboundRequest("GET", URI.create(uri), Map.of("Accept", "application/json"), null);
    }

    @Override
    public List<SeriesData> parse(String body, MetricQuery query) {
        JsonNode root = TelemetryJson.read(body, JsonNode.class);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Prometheus response is not JSON");
        }
        if (!"success".equals(root.path("status").asText())) {
            throw new IllegalArgumentException("Prometheus query failed: " + root.path("errorType").asText("error") + " "
                    + OtlpJsonParser.truncate(root.path("error").asText(""), 300));
        }
        JsonNode data = root.path("data");
        String type = data.path("resultType").asText();
        List<SeriesData> out = new ArrayList<>();
        for (JsonNode r : data.path("result")) {
            Map<String, String> labels = new LinkedHashMap<>();
            r.path("metric").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
            List<Sample> samples = new ArrayList<>();
            if ("matrix".equals(type)) {
                for (JsonNode v : r.path("values")) {
                    Sample s = sample(v);
                    if (s != null) {
                        samples.add(s);
                    }
                }
            } else if ("vector".equals(type)) {
                Sample s = sample(r.path("value"));
                if (s != null) {
                    samples.add(s);
                }
            } else {
                throw new IllegalArgumentException("Unsupported Prometheus resultType '" + type + "' (matrix or vector expected)");
            }
            Map<String, String> resource = new LinkedHashMap<>();
            String service = labels.getOrDefault("service", labels.get("job"));
            if (service != null) {
                resource.put("service.name", service);
            }
            String instance = labels.get("instance");
            if (instance != null) {
                resource.put("host.name", hostOf(instance));
            }
            if (labels.containsKey("namespace")) {
                resource.put("k8s.namespace.name", labels.get("namespace"));
            }
            Map<String, String> attributes = new LinkedHashMap<>(labels);
            attributes.remove("__name__");
            attributes.remove("service");
            attributes.remove("job");
            attributes.remove("instance");
            attributes.remove("namespace");
            out.add(new SeriesData(resource, query.name(), attributes, samples));
        }
        return out;
    }

    /** {@code [<unix seconds>, "<value>"]}; NaN / ±Inf samples are skipped. */
    private static Sample sample(JsonNode v) {
        if (!v.isArray() || v.size() != 2) {
            return null;
        }
        double seconds = v.get(0).asDouble();
        double value;
        try {
            value = Double.parseDouble(v.get(1).asText());
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        long millis = Math.round(seconds * 1000);
        return new Sample(Instant.ofEpochMilli(millis), value);
    }

    static String hostOf(String instance) {
        String i = instance.trim();
        if (i.startsWith("[")) {
            int end = i.indexOf(']');
            return end > 0 ? i.substring(1, end) : i;
        }
        int colon = i.lastIndexOf(':');
        return colon > 0 && i.indexOf(':') == colon ? i.substring(0, colon) : i;
    }

    @Override
    public OutboundRequest buildTest(String baseUrl, Map<String, Object> config) {
        if (baseUrl == null) {
            throw ApiException.badRequest("A Prometheus source needs a base URL");
        }
        return new OutboundRequest("GET", URI.create(TelemetryAdapter.stripSlash(baseUrl) + "/api/v1/status/buildinfo"), Map.of("Accept", "application/json"),
                null);
    }

    @Override
    public boolean testSucceeded(int status, String body) {
        JsonNode root = TelemetryJson.read(body, JsonNode.class);
        return status == 200 && root != null && "success".equals(root.path("status").asText());
    }
}

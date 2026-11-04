package com.nexus360.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Prepared: Grafana data source proxy query, {@code POST /api/ds/query} with a service-account token (bearer). Config:
 * {@code datasourceUid} and {@code queries: [{name, expr}]}. The response's data frames ({@code results.<refId>.frames[]}: a time field
 * and number fields with labels) become series. Test call: {@code GET /api/datasources/uid/<datasourceUid>} (checks the token and the
 * data source). Scheduled pull is not enabled for prepared adapters.
 */
@Component
public class GrafanaAdapter implements TelemetryAdapter {

    @Override
    public TelemetrySourceKind kind() {
        return TelemetrySourceKind.GRAFANA;
    }

    @Override
    public boolean prepared() {
        return true;
    }

    @Override
    public void validate(Map<String, Object> config) {
        String uid = TelemetryAdapter.requireString(config, "datasourceUid", 100);
        if (!uid.matches("^[A-Za-z0-9_\\-]{1,100}$")) {
            throw ApiException.invalidField("config.datasourceUid", "datasourceUid must be a Grafana data source uid");
        }
        TelemetryAdapter.queryList(config, List.of("name", "expr"));
    }

    @Override
    public List<MetricQuery> queries(Map<String, Object> config) {
        return TelemetryAdapter.queryList(config, List.of("name", "expr")).stream()
                .map(q -> new MetricQuery(String.valueOf(q.get("name")).trim(), String.valueOf(q.get("expr")).trim(), Map.of())).toList();
    }

    @Override
    public OutboundRequest buildQuery(String baseUrl, Map<String, Object> config, MetricQuery query, Instant from, Instant to, Duration step) {
        ObjectNode body = TelemetryJson.MAPPER.createObjectNode();
        body.put("from", String.valueOf(from.toEpochMilli()));
        body.put("to", String.valueOf(to.toEpochMilli()));
        ArrayNode queries = body.putArray("queries");
        ObjectNode q = queries.addObject();
        q.put("refId", "A");
        q.putObject("datasource").put("uid", TelemetryAdapter.requireString(config, "datasourceUid", 100));
        q.put("expr", query.expression());
        q.put("intervalMs", Math.max(1000, step.toMillis()));
        q.put("maxDataPoints", 1000);
        return new OutboundRequest("POST", URI.create(TelemetryAdapter.stripSlash(baseUrl) + "/api/ds/query"),
                Map.of("Content-Type", "application/json", "Accept", "application/json"), body.toString());
    }

    @Override
    public List<SeriesData> parse(String body, MetricQuery query) {
        JsonNode root = TelemetryJson.read(body, JsonNode.class);
        if (root == null || !root.has("results")) {
            throw new IllegalArgumentException("Grafana response has no 'results'");
        }
        List<SeriesData> out = new ArrayList<>();
        root.path("results").fields().forEachRemaining(result -> {
            if (result.getValue().has("error")) {
                throw new IllegalArgumentException("Grafana query " + result.getKey() + " failed: "
                        + OtlpJsonParser.truncate(result.getValue().path("error").asText(), 300));
            }
            for (JsonNode frame : result.getValue().path("frames")) {
                JsonNode fields = frame.path("schema").path("fields");
                JsonNode values = frame.path("data").path("values");
                int timeIndex = -1;
                for (int i = 0; i < fields.size(); i++) {
                    if ("time".equals(fields.get(i).path("type").asText())) {
                        timeIndex = i;
                        break;
                    }
                }
                if (timeIndex < 0) {
                    continue;
                }
                JsonNode times = values.path(timeIndex);
                for (int i = 0; i < fields.size(); i++) {
                    if (i == timeIndex || !"number".equals(fields.get(i).path("type").asText())) {
                        continue;
                    }
                    Map<String, String> labels = new LinkedHashMap<>();
                    fields.get(i).path("labels").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
                    List<Sample> samples = new ArrayList<>();
                    JsonNode column = values.path(i);
                    for (int k = 0; k < times.size() && k < column.size(); k++) {
                        if (column.get(k).isNumber()) {
                            samples.add(new Sample(Instant.ofEpochMilli(times.get(k).asLong()), column.get(k).asDouble()));
                        }
                    }
                    Map<String, String> resource = new LinkedHashMap<>();
                    String service = labels.getOrDefault("service", labels.get("job"));
                    if (service != null) {
                        resource.put("service.name", service);
                    }
                    if (labels.containsKey("instance")) {
                        resource.put("host.name", PrometheusAdapter.hostOf(labels.get("instance")));
                    }
                    Map<String, String> attributes = new LinkedHashMap<>(labels);
                    attributes.keySet().removeAll(List.of("__name__", "service", "job", "instance"));
                    out.add(new SeriesData(resource, query.name(), attributes, samples));
                }
            }
        });
        return out;
    }

    @Override
    public OutboundRequest buildTest(String baseUrl, Map<String, Object> config) {
        if (baseUrl == null) {
            throw ApiException.badRequest("A Grafana source needs a base URL");
        }
        return new OutboundRequest("GET", URI.create(TelemetryAdapter.stripSlash(baseUrl) + "/api/datasources/uid/"
                + TelemetryAdapter.encode(TelemetryAdapter.requireString(config, "datasourceUid", 100))), Map.of("Accept", "application/json"), null);
    }
}

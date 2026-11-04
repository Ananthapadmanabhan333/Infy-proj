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
 * Prepared: Google Cloud Monitoring {@code projects.timeSeries.list} ({@code GET https://monitoring.googleapis.com/v3/projects/<id>/timeSeries}
 * with {@code filter}, {@code interval.startTime/endTime}, {@code aggregation.alignmentPeriod} and {@code perSeriesAligner}). Config:
 * {@code projectId} and {@code queries: [{name, filter, aligner?: ALIGN_MEAN}]}. Authentication: an OAuth 2.0 access token supplied as
 * the source secret (bearer); service-account token exchange is not implemented in this release. {@code timeSeries[].points[]}
 * ({@code interval.endTime}, {@code value.doubleValue|int64Value}) become series with {@code cloud.provider=gcp}, the monitored
 * resource labels and the metric labels.
 */
@Component
public class GcpMonitoringAdapter implements TelemetryAdapter {

    @Override
    public TelemetrySourceKind kind() {
        return TelemetrySourceKind.GCP_MONITORING;
    }

    @Override
    public boolean prepared() {
        return true;
    }

    @Override
    public String defaultBaseUrl(Map<String, Object> config) {
        return "https://monitoring.googleapis.com";
    }

    @Override
    public void validate(Map<String, Object> config) {
        String project = TelemetryAdapter.requireString(config, "projectId", 64);
        if (!project.matches("^[a-z][a-z0-9\\-]{4,62}[a-z0-9]$")) {
            throw ApiException.invalidField("config.projectId", "projectId must be a Google Cloud project id");
        }
        for (Map<String, Object> q : TelemetryAdapter.queryList(config, List.of("name", "filter"))) {
            Object aligner = q.get("aligner");
            if (aligner != null && !String.valueOf(aligner).matches("^ALIGN_[A-Z_0-9]+$")) {
                throw ApiException.invalidField("config.queries", "aligner must be a perSeriesAligner such as ALIGN_MEAN");
            }
        }
    }

    @Override
    public List<MetricQuery> queries(Map<String, Object> config) {
        return TelemetryAdapter.queryList(config, List.of("name", "filter")).stream()
                .map(q -> new MetricQuery(String.valueOf(q.get("name")).trim(), String.valueOf(q.get("filter")).trim(),
                        TelemetryAdapter.stringOptions(q, "aligner"))).toList();
    }

    @Override
    public OutboundRequest buildQuery(String baseUrl, Map<String, Object> config, MetricQuery query, Instant from, Instant to, Duration step) {
        String project = TelemetryAdapter.requireString(config, "projectId", 64);
        String uri = TelemetryAdapter.stripSlash(baseUrl) + "/v3/projects/" + project + "/timeSeries?filter=" + TelemetryAdapter.encode(query.expression())
                + "&interval.startTime=" + TelemetryAdapter.encode(from.toString()) + "&interval.endTime=" + TelemetryAdapter.encode(to.toString())
                + "&aggregation.alignmentPeriod=" + Math.max(60, step.toSeconds()) + "s&aggregation.perSeriesAligner="
                + query.options().getOrDefault("aligner", "ALIGN_MEAN");
        return new OutboundRequest("GET", URI.create(uri), Map.of("Accept", "application/json"), null);
    }

    @Override
    public List<SeriesData> parse(String body, MetricQuery query) {
        JsonNode root = TelemetryJson.read(body, JsonNode.class);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Cloud Monitoring response is not JSON");
        }
        if (root.has("error")) {
            throw new IllegalArgumentException("Cloud Monitoring error: " + OtlpJsonParser.truncate(root.path("error").path("message").asText(), 300));
        }
        List<SeriesData> out = new ArrayList<>();
        for (JsonNode ts : root.path("timeSeries")) {
            Map<String, String> resource = new LinkedHashMap<>();
            resource.put("cloud.provider", "gcp");
            JsonNode res = ts.path("resource");
            resource.put("gcp.resource_type", res.path("type").asText(""));
            res.path("labels").fields().forEachRemaining(e -> {
                switch (e.getKey()) {
                    case "zone", "location", "region" -> resource.put("cloud.region", e.getValue().asText());
                    case "instance_id" -> resource.put("host.id", e.getValue().asText());
                    case "project_id" -> resource.put("cloud.account.id", e.getValue().asText());
                    default -> resource.put("gcp." + e.getKey(), e.getValue().asText());
                }
            });
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("metric.type", ts.path("metric").path("type").asText(""));
            ts.path("metric").path("labels").fields().forEachRemaining(e -> attributes.put(e.getKey(), e.getValue().asText()));
            List<Sample> samples = new ArrayList<>();
            for (JsonNode p : ts.path("points")) {
                JsonNode v = p.path("value");
                Double value = v.has("doubleValue") ? Double.valueOf(v.path("doubleValue").asDouble())
                        : v.has("int64Value") ? Double.valueOf(v.path("int64Value").asText()) : null;
                String end = p.path("interval").path("endTime").asText(null);
                if (value != null && end != null) {
                    samples.add(new Sample(Instant.parse(end), value));
                }
            }
            samples.sort(java.util.Comparator.comparing(Sample::timestamp));
            out.add(new SeriesData(resource, query.name(), attributes, samples));
        }
        return out;
    }

    @Override
    public OutboundRequest buildTest(String baseUrl, Map<String, Object> config) {
        String project = TelemetryAdapter.requireString(config, "projectId", 64);
        return new OutboundRequest("GET", URI.create(TelemetryAdapter.stripSlash(baseUrl) + "/v3/projects/" + project + "/metricDescriptors?pageSize=1"),
                Map.of("Accept", "application/json"), null);
    }
}

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
 * Prepared: Amazon CloudWatch {@code GetMetricData} (AWS JSON 1.0 protocol, {@code X-Amz-Target:
 * GraniteServiceVersion20100801.GetMetricData}) against {@code https://monitoring.<region>.amazonaws.com/}. Config: {@code region} and
 * {@code queries: [{name, namespace, metricName, dimensions?: {Name: Value}, stat?: Average, period?: 60}]}. The response's
 * {@code MetricDataResults[]} ({@code Timestamps} in epoch seconds, {@code Values}) become series with {@code cloud.provider=aws},
 * {@code cloud.region} and the dimensions as attributes.
 *
 * <p>Requests must be signed with AWS Signature Version 4, which this release does not implement: the source stays
 * {@code NOT_CONNECTED} and the test call reports that.
 */
@Component
public class CloudWatchAdapter implements TelemetryAdapter {

    static final String TARGET = "GraniteServiceVersion20100801.GetMetricData";

    @Override
    public TelemetrySourceKind kind() {
        return TelemetrySourceKind.CLOUDWATCH;
    }

    @Override
    public boolean prepared() {
        return true;
    }

    @Override
    public String defaultBaseUrl(Map<String, Object> config) {
        return "https://monitoring." + TelemetryAdapter.optionalString(config, "region", "us-east-1") + ".amazonaws.com";
    }

    @Override
    public void validate(Map<String, Object> config) {
        String region = TelemetryAdapter.requireString(config, "region", 30);
        if (!region.matches("^[a-z]{2}(-[a-z]+)+-\\d$")) {
            throw ApiException.invalidField("config.region", "region must be an AWS region such as ap-south-1");
        }
        for (Map<String, Object> q : TelemetryAdapter.queryList(config, List.of("name", "namespace", "metricName"))) {
            Object stat = q.get("stat");
            if (stat != null && !List.of("Average", "Sum", "Minimum", "Maximum", "SampleCount", "p95", "p99").contains(String.valueOf(stat))) {
                throw ApiException.invalidField("config.queries", "stat must be Average, Sum, Minimum, Maximum, SampleCount, p95 or p99");
            }
            if (q.get("dimensions") != null && !(q.get("dimensions") instanceof Map<?, ?>)) {
                throw ApiException.invalidField("config.queries", "dimensions must be an object of name → value");
            }
        }
    }

    @Override
    public List<MetricQuery> queries(Map<String, Object> config) {
        return TelemetryAdapter.queryList(config, List.of("name", "namespace", "metricName")).stream()
                .map(q -> new MetricQuery(String.valueOf(q.get("name")).trim(), String.valueOf(q.get("metricName")).trim(),
                        TelemetryAdapter.stringOptions(q, "namespace", "dimensions", "stat", "period"))).toList();
    }

    @Override
    public OutboundRequest buildQuery(String baseUrl, Map<String, Object> config, MetricQuery query, Instant from, Instant to, Duration step) {
        ObjectNode body = TelemetryJson.MAPPER.createObjectNode();
        body.put("StartTime", from.getEpochSecond());
        body.put("EndTime", to.getEpochSecond());
        body.put("ScanBy", "TimestampAscending");
        ArrayNode queries = body.putArray("MetricDataQueries");
        ObjectNode q = queries.addObject();
        q.put("Id", "m1");
        ObjectNode stat = q.putObject("MetricStat");
        ObjectNode metric = stat.putObject("Metric");
        metric.put("Namespace", query.options().get("namespace"));
        metric.put("MetricName", query.expression());
        ArrayNode dims = metric.putArray("Dimensions");
        Map<String, Object> dimensions = TelemetryJson.objectMap(query.options().get("dimensions"));
        dimensions.forEach((k, v) -> dims.addObject().put("Name", k).put("Value", String.valueOf(v)));
        int period = query.options().containsKey("period") ? Integer.parseInt(query.options().get("period")) : (int) Math.max(60, step.toSeconds());
        stat.put("Period", period);
        stat.put("Stat", query.options().getOrDefault("stat", "Average"));
        q.put("ReturnData", true);
        return new OutboundRequest("POST", URI.create(TelemetryAdapter.stripSlash(baseUrl) + "/"), Map.of("Content-Type", "application/x-amz-json-1.0",
                "X-Amz-Target", TARGET, "Accept", "application/json"), body.toString());
    }

    @Override
    public List<SeriesData> parse(String body, MetricQuery query) {
        JsonNode root = TelemetryJson.read(body, JsonNode.class);
        if (root == null || !root.has("MetricDataResults")) {
            throw new IllegalArgumentException("CloudWatch response has no MetricDataResults");
        }
        List<SeriesData> out = new ArrayList<>();
        for (JsonNode r : root.path("MetricDataResults")) {
            JsonNode ts = r.path("Timestamps");
            JsonNode values = r.path("Values");
            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i < ts.size() && i < values.size(); i++) {
                JsonNode t = ts.get(i);
                Instant at = t.isNumber() ? Instant.ofEpochMilli(Math.round(t.asDouble() * 1000)) : Instant.parse(t.asText());
                samples.add(new Sample(at, values.get(i).asDouble()));
            }
            Map<String, String> resource = new LinkedHashMap<>();
            resource.put("cloud.provider", "aws");
            Map<String, String> attributes = new LinkedHashMap<>();
            Map<String, Object> dimensions = TelemetryJson.objectMap(query.options().get("dimensions"));
            dimensions.forEach((k, v) -> attributes.put(k, String.valueOf(v)));
            if (dimensions.containsKey("InstanceId")) {
                resource.put("host.id", String.valueOf(dimensions.get("InstanceId")));
            }
            attributes.put("namespace", query.options().getOrDefault("namespace", ""));
            if (r.hasNonNull("Label")) {
                attributes.put("label", r.path("Label").asText());
            }
            out.add(new SeriesData(resource, query.name(), attributes, samples));
        }
        return out;
    }

    @Override
    public OutboundRequest buildTest(String baseUrl, Map<String, Object> config) {
        return null;
    }

    @Override
    public String testUnsupportedReason() {
        return "Prepared adapter: CloudWatch requires AWS Signature Version 4 request signing, which this release does not implement. "
                + "The request builder and response parser are ready; the source stays NOT_CONNECTED.";
    }
}

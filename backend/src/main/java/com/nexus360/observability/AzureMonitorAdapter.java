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
 * Prepared: Azure Monitor metrics REST API, {@code GET https://management.azure.com<resourceId>/providers/Microsoft.Insights/metrics}
 * ({@code api-version=2023-10-01}, ISO-8601 {@code timespan}, {@code interval=PT1M}). Config: {@code resourceId} and
 * {@code queries: [{name, metricName, aggregation?: Average}]}. Authentication: a Microsoft Entra ID access token supplied as the
 * source secret (bearer); token acquisition (client credentials) is not implemented in this release. Response {@code value[]
 * .timeseries[].data[]} ({@code timeStamp} + the aggregation field) becomes series with {@code cloud.provider=azure}, the resource id
 * and the time-series metadata values as attributes.
 */
@Component
public class AzureMonitorAdapter implements TelemetryAdapter {

    static final String API_VERSION = "2023-10-01";

    @Override
    public TelemetrySourceKind kind() {
        return TelemetrySourceKind.AZURE_MONITOR;
    }

    @Override
    public boolean prepared() {
        return true;
    }

    @Override
    public String defaultBaseUrl(Map<String, Object> config) {
        return "https://management.azure.com";
    }

    @Override
    public void validate(Map<String, Object> config) {
        String resourceId = TelemetryAdapter.requireString(config, "resourceId", 500);
        if (!resourceId.matches("^/subscriptions/[^/]+/resourceGroups/[^/]+/providers/.+$")) {
            throw ApiException.invalidField("config.resourceId", "resourceId must be an Azure resource id (/subscriptions/.../providers/...)");
        }
        for (Map<String, Object> q : TelemetryAdapter.queryList(config, List.of("name", "metricName"))) {
            Object agg = q.get("aggregation");
            if (agg != null && !List.of("Average", "Total", "Minimum", "Maximum", "Count").contains(String.valueOf(agg))) {
                throw ApiException.invalidField("config.queries", "aggregation must be Average, Total, Minimum, Maximum or Count");
            }
        }
    }

    @Override
    public List<MetricQuery> queries(Map<String, Object> config) {
        return TelemetryAdapter.queryList(config, List.of("name", "metricName")).stream()
                .map(q -> new MetricQuery(String.valueOf(q.get("name")).trim(), String.valueOf(q.get("metricName")).trim(),
                        TelemetryAdapter.stringOptions(q, "aggregation"))).toList();
    }

    @Override
    public OutboundRequest buildQuery(String baseUrl, Map<String, Object> config, MetricQuery query, Instant from, Instant to, Duration step) {
        String resourceId = TelemetryAdapter.requireString(config, "resourceId", 500);
        String aggregation = query.options().getOrDefault("aggregation", "Average");
        long minutes = Math.max(1, step.toMinutes());
        String uri = TelemetryAdapter.stripSlash(baseUrl) + resourceId + "/providers/Microsoft.Insights/metrics?api-version=" + API_VERSION + "&metricnames="
                + TelemetryAdapter.encode(query.expression()) + "&timespan=" + TelemetryAdapter.encode(from + "/" + to) + "&interval=PT" + minutes + "M"
                + "&aggregation=" + aggregation;
        return new OutboundRequest("GET", URI.create(uri), Map.of("Accept", "application/json"), null);
    }

    @Override
    public List<SeriesData> parse(String body, MetricQuery query) {
        JsonNode root = TelemetryJson.read(body, JsonNode.class);
        if (root == null || !root.has("value")) {
            throw new IllegalArgumentException("Azure Monitor response has no 'value'");
        }
        String field = switch (query.options().getOrDefault("aggregation", "Average")) {
            case "Total" -> "total";
            case "Minimum" -> "minimum";
            case "Maximum" -> "maximum";
            case "Count" -> "count";
            default -> "average";
        };
        List<SeriesData> out = new ArrayList<>();
        for (JsonNode metric : root.path("value")) {
            for (JsonNode series : metric.path("timeseries")) {
                Map<String, String> attributes = new LinkedHashMap<>();
                for (JsonNode md : series.path("metadatavalues")) {
                    attributes.put(md.path("name").path("value").asText(), md.path("value").asText());
                }
                if (metric.hasNonNull("unit")) {
                    attributes.put("unit", metric.path("unit").asText());
                }
                List<Sample> samples = new ArrayList<>();
                for (JsonNode d : series.path("data")) {
                    if (d.hasNonNull(field) && d.hasNonNull("timeStamp")) {
                        samples.add(new Sample(Instant.parse(d.path("timeStamp").asText()), d.path(field).asDouble()));
                    }
                }
                Map<String, String> resource = new LinkedHashMap<>();
                resource.put("cloud.provider", "azure");
                if (root.hasNonNull("resourceregion")) {
                    resource.put("cloud.region", root.path("resourceregion").asText());
                }
                String id = metric.path("id").asText("");
                int cut = id.indexOf("/providers/Microsoft.Insights/metrics");
                resource.put("cloud.resource_id", cut > 0 ? id.substring(0, cut) : id);
                out.add(new SeriesData(resource, query.name(), attributes, samples));
            }
        }
        return out;
    }

    @Override
    public OutboundRequest buildTest(String baseUrl, Map<String, Object> config) {
        String resourceId = TelemetryAdapter.requireString(config, "resourceId", 500);
        return new OutboundRequest("GET", URI.create(TelemetryAdapter.stripSlash(baseUrl) + resourceId
                + "/providers/Microsoft.Insights/metricDefinitions?api-version=2018-01-01"), Map.of("Accept", "application/json"), null);
    }
}

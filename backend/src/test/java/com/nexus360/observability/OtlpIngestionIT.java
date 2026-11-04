package com.nexus360.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus360.observability.OtlpPayloads.Gauge;
import com.nexus360.observability.OtlpPayloads.Histogram;
import com.nexus360.observability.OtlpPayloads.Log;
import com.nexus360.observability.OtlpPayloads.Span;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * OTLP/HTTP JSON ingestion end to end: metrics, logs and traces (valid and partial success), authentication (401), protobuf (415),
 * size limit (413), malformed bodies (400), gzip, mapping rules and unmapped resources, roll-ups and retention, and the query APIs.
 */
class OtlpIngestionIT extends ObservabilityTestSupport {

    @Autowired
    TelemetryMaintenanceService maintenance;

    @Test
    void metricsAreIngestedWithPartialSuccessAndTheSourceBecomesConnected() throws Exception {
        String token = otlpSource("OTLP metrics " + uid());
        JsonNode source = getJson("admin", "/api/observability/sources").findParents("tokenHint").stream()
                .filter(s -> token.startsWith(s.get("tokenHint").asText())).findFirst().orElseThrow();
        assertThat(source.get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(source.toString()).doesNotContain(token).doesNotContain("token_hash");
        assertThat(source.get("ingestPaths").get("metrics").asText()).isEqualTo("/api/otel/v1/metrics");

        String service = "otlp-metrics-" + uid();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        ObjectNode body = OtlpPayloads.metrics(resource(service), List.of(new Gauge("test.cpu", "1", Map.of("core", "0"), now.minusSeconds(30), 0.42),
                new Histogram("http.server.request.duration", "ms", Map.of("http.route", "/a"), now.minusSeconds(30), 10, 1500, 20, 400),
                new OtlpPayloads.Sum("test.requests", "1", Map.of(), now.minusSeconds(30), 17, true)));
        MvcResult ok = export("metrics", token, body);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).has("partialSuccess")).isFalse();
        JsonNode after = getJson("admin", "/api/observability/sources/" + source.get("id").asLong());
        assertThat(after.get("status").asText()).isEqualTo("CONNECTED");
        assertThat(after.get("lastVerifiedAt").isNull()).isFalse();
        assertThat(after.get("receivedCount").asLong()).isEqualTo(3);

        // Two invalid points (no timestamp, NaN) and one valid one: partial success.
        ObjectNode partial = OtlpPayloads.metrics(resource(service), List.of(new Gauge("test.cpu", "1", Map.of("core", "0"), now.minusSeconds(20), 0.5)));
        ObjectNode points = (ObjectNode) partial.at("/resourceMetrics/0/scopeMetrics/0/metrics/0/gauge/dataPoints/0");
        partial.withArray("/resourceMetrics/0/scopeMetrics/0/metrics/0/gauge/dataPoints").addObject().put("asDouble", 1.0);
        partial.withArray("/resourceMetrics/0/scopeMetrics/0/metrics/0/gauge/dataPoints").addObject().put("timeUnixNano", points.get("timeUnixNano").asText())
                .put("asDouble", "NaN");
        MvcResult p = export("metrics", token, partial);
        assertThat(p.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(p).at("/partialSuccess/rejectedDataPoints").asInt()).isEqualTo(2);
        assertThat(body(p).at("/partialSuccess/errorMessage").asText()).contains("timeUnixNano");

        JsonNode series = getJson("riya.varma", "/api/observability/metrics/series?name=http.server.request.duration&attr.http.route=/a&from="
                + now.minus(Duration.ofMinutes(10)) + "&to=" + now.plus(Duration.ofMinutes(2)));
        JsonNode mine = findSeries(series, service);
        assertThat(mine.get("type").asText()).isEqualTo("HISTOGRAM");
        assertThat(mine.get("points").get(0).get("v").asDouble()).isEqualTo(150.0);
        assertThat(getJson("riya.varma", "/api/observability/metrics?search=test.cpu").toString()).contains("test.cpu");
    }

    @Test
    void authenticationContentTypeSizeAndMalformedBodiesAreRejected() throws Exception {
        String token = otlpSource("OTLP guard " + uid());
        ObjectNode body = OtlpPayloads.metrics(resource("guard-" + uid()), List.of(new Gauge("g", null, Map.of(), clock.instant(), 1)));
        assertThat(export("metrics", "otsec_wrong000000000000000000000000000000000", body).getResponse().getStatus()).isEqualTo(401);
        MvcResult noToken = mvc.perform(post("/api/otel/v1/logs").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(noToken.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(noToken).get("code").asInt()).isEqualTo(16);
        // A user's JWT is not an OTLP token.
        assertThat(mvc.perform(as("admin", post("/api/otel/v1/traces")).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse()
                .getStatus()).isEqualTo(401);

        MvcResult protobuf = mvc.perform(post("/api/otel/v1/metrics").header("Authorization", "Bearer " + token).contentType("application/x-protobuf")
                .content(new byte[] {10, 2, 8, 1})).andReturn();
        assertThat(protobuf.getResponse().getStatus()).isEqualTo(415);
        assertThat(body(protobuf).get("message").asText()).contains("protobuf is not supported");
        assertThat(mvc.perform(post("/api/otel/v1/metrics").header("Authorization", "Bearer " + token).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andReturn().getResponse().getStatus()).isEqualTo(415);

        StringBuilder big = new StringBuilder("{\"resourceMetrics\":[],\"padding\":\"");
        big.append("x".repeat(2 * 1024 * 1024 + 10)).append("\"}");
        assertThat(export("metrics", token, big.toString()).getResponse().getStatus()).isEqualTo(413);

        assertThat(export("logs", token, "{not json").getResponse().getStatus()).isEqualTo(400);
        assertThat(export("logs", token, "[1,2]").getResponse().getStatus()).isEqualTo(400);

        // gzip-compressed JSON is accepted.
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        }
        MvcResult zipped = mvc.perform(post("/api/otel/v1/metrics").header("Authorization", "Bearer " + token).header("Content-Encoding", "gzip")
                .contentType(MediaType.APPLICATION_JSON).content(gz.toByteArray())).andReturn();
        assertThat(zipped.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'OTLP_REJECTED'", Long.class)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void logsAreNormalisedAndSearchable() throws Exception {
        String token = otlpSource("OTLP logs " + uid());
        String service = "otlp-logs-" + uid();
        Instant t = clock.instant().minusSeconds(60);
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        ObjectNode body = OtlpPayloads.logs(resource(service), List.of(
                new Log(t, 17, "ERROR", "Connection refused by upstream " + service, Map.of("k", "v"), traceId, "b7ad6b7169203331"),
                new Log(t.plusSeconds(1), 9, "INFO", "all good", Map.of(), null, null),
                new Log(t.plusSeconds(2), 0, "warning", "disk 85% full", Map.of(), null, null),
                new Log(t.plusSeconds(3), 9, "INFO", "x".repeat(9000), Map.of(), null, null)));
        // Invalid trace id → rejected.
        body.withArray("/resourceLogs/0/scopeLogs/0/logRecords").addObject().put("timeUnixNano", OtlpPayloads.nanos(t)).put("traceId", "xyz");
        MvcResult r = export("logs", token, body);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).at("/partialSuccess/rejectedLogRecords").asInt()).isEqualTo(1);

        JsonNode errors = getJson("ananya.bose", "/api/observability/logs?severity=WARN&text=" + service);
        assertThat(errors.get("totalElements").asLong()).isEqualTo(1);
        JsonNode first = errors.get("content").get(0);
        assertThat(first.get("severity").asText()).isEqualTo("ERROR");
        assertThat(first.get("traceId").asText()).isEqualTo(traceId);
        assertThat(first.get("simulated").asBoolean()).isFalse();
        JsonNode warn = getJson("ananya.bose", "/api/observability/logs?text=disk&from=" + t.minusSeconds(5) + "&to=" + t.plusSeconds(10));
        assertThat(warn.get("content").get(0).get("severity").asText()).isEqualTo("WARN");
        JsonNode longBody = getJson("ananya.bose", "/api/observability/logs?text=xxxxxxxxxx&from=" + t.minusSeconds(5) + "&to=" + t.plusSeconds(10));
        JsonNode truncated = longBody.get("content").get(0);
        assertThat(truncated.get("bodyTruncated").asBoolean()).isTrue();
        assertThat(truncated.get("body").asText()).hasSize(8192);
        mvc.perform(as("ananya.bose", get("/api/observability/logs?severity=LOUD"))).andExpect(status().isBadRequest());
    }

    @Test
    void tracesBuildSpanTreesAndRedSummaries() throws Exception {
        String token = otlpSource("OTLP traces " + uid());
        String code = ("OTL" + uid()).toUpperCase().replace("-", "");
        long[] svc = service(code);
        String serviceName = "otlp-traces-" + uid();
        mvc.perform(jsonBody(as("admin", post("/api/observability/mappings")), Map.of("name", "map " + serviceName, "attributeKey", "service.name",
                "matchType", "EXACT", "matchValue", serviceName, "serviceId", svc[0]))).andExpect(status().isCreated());
        Instant t = clock.instant().minusSeconds(120);
        String trace = "4bf92f3577b34da6a3ce929d0e0e4736";
        List<Span> spans = List.of(new Span(trace, "00f067aa0ba902b7", null, "GET /orders", 2, t, t.plusMillis(250), 2, "boom", Map.of()),
                new Span(trace, "00f067aa0ba902b8", "00f067aa0ba902b7", "SELECT orders", 3, t.plusMillis(10), t.plusMillis(200), 0, null, Map.of()),
                new Span(trace, "00f067aa0ba902b9", "00f067aa0ba902b8", "fetch", 1, t.plusMillis(20), t.plusMillis(60), 0, null, Map.of()),
                new Span("4bf92f3577b34da6a3ce929d0e0e4737", "10f067aa0ba902b7", null, "GET /health", 2, t, t.plusMillis(50), 1, null, Map.of()));
        ObjectNode body = OtlpPayloads.traces(resource(serviceName), spans);
        body.withArray("/resourceSpans/0/scopeSpans/0/spans").addObject().put("traceId", trace).put("spanId", "zz").put("name", "bad")
                .put("startTimeUnixNano", OtlpPayloads.nanos(t)).put("endTimeUnixNano", OtlpPayloads.nanos(t));
        MvcResult r = export("traces", token, body);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).at("/partialSuccess/rejectedSpans").asInt()).isEqualTo(1);
        // Retried export of the same spans is accepted and stored once.
        assertThat(export("traces", token, OtlpPayloads.traces(resource(serviceName), spans)).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from telemetry_spans where trace_id = ?", Long.class, trace)).isEqualTo(3);

        JsonNode detail = getJson("riya.varma", "/api/observability/traces/" + trace);
        assertThat(detail.get("spanCount").asInt()).isEqualTo(3);
        assertThat(detail.get("errorCount").asInt()).isEqualTo(1);
        JsonNode root = detail.get("roots").get(0);
        assertThat(root.get("name").asText()).isEqualTo("GET /orders");
        assertThat(root.get("kind").asText()).isEqualTo("SERVER");
        assertThat(root.get("children").get(0).get("name").asText()).isEqualTo("SELECT orders");
        assertThat(root.get("children").get(0).get("children").get(0).get("name").asText()).isEqualTo("fetch");
        assertThat(root.get("durationMs").asDouble()).isEqualTo(250.0);

        JsonNode list = getJson("riya.varma", "/api/observability/traces?serviceId=" + svc[0] + "&error=true");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("traceId").asText()).isEqualTo(trace);
        assertThat(getJson("riya.varma", "/api/observability/traces?serviceId=" + svc[0])).hasSize(2);

        JsonNode red = getJson("riya.varma", "/api/observability/services/" + svc[0] + "/red?from=" + t.minusSeconds(60) + "&to=" + t.plusSeconds(120));
        assertThat(red.get("basis").asText()).isEqualTo("SPANS");
        assertThat(red.get("requests").asLong()).isEqualTo(2);
        assertThat(red.get("errors").asLong()).isEqualTo(1);
        assertThat(red.get("errorRate").asDouble()).isEqualTo(0.5);
        assertThat(red.get("durationP95Ms").asDouble()).isBetween(200.0, 250.0);

        long[] empty = service(("NOD" + uid()).toUpperCase());
        JsonNode none = getJson("riya.varma", "/api/observability/services/" + empty[0] + "/red");
        assertThat(none.get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(none.get("ratePerMinute").isNull()).isTrue();
        mvc.perform(as("riya.varma", get("/api/observability/traces/nothex"))).andExpect(status().isBadRequest());
    }

    @Test
    void mappingRulesMapResourcesAndUnmappedResourcesAreListed() throws Exception {
        String token = otlpSource("OTLP mapping " + uid());
        String code = ("MAP" + uid()).toUpperCase();
        long[] svc = service(code);
        String mapped = "mapped-" + uid();
        String unmapped = "unmapped-" + uid();
        Instant t = clock.instant().minusSeconds(30);
        export("metrics", token, OtlpPayloads.metrics(resource(unmapped), List.of(new Gauge("m", null, Map.of(), t, 1))));
        JsonNode unmappedList = getJson("admin", "/api/observability/resources?mapped=false&search=" + unmapped);
        assertThat(unmappedList.get("totalElements").asLong()).isEqualTo(1);
        assertThat(unmappedList.get("content").get(0).get("serviceId").isNull()).isTrue();

        // Regex rule: maps both the new resource (at ingestion) and, after reapply, the existing unmapped one.
        MvcResult rule = mvc.perform(jsonBody(as("admin", post("/api/observability/mappings")), Map.of("name", "regex " + code, "attributeKey", "service.name",
                "matchType", "REGEX", "matchValue", "(mapped|unmapped)-" + mapped.substring(7) + "|" + unmapped, "serviceId", svc[0], "sortOrder", 5)))
                .andExpect(status().isCreated()).andReturn();
        export("metrics", token, OtlpPayloads.metrics(resource(mapped), List.of(new Gauge("m", null, Map.of(), t, 2))));
        JsonNode m = getJson("admin", "/api/observability/resources?mapped=true&search=" + mapped);
        assertThat(m.get("content").get(0).get("serviceId").asLong()).isEqualTo(svc[0]);
        assertThat(m.get("content").get(0).get("mappingRuleId").asLong()).isEqualTo(body(rule).get("id").asLong());
        JsonNode reapply = body(mvc.perform(as("admin", post("/api/observability/mappings/reapply"))).andExpect(status().isOk()).andReturn());
        assertThat(reapply.get("changed").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(getJson("admin", "/api/observability/resources?mapped=false&search=" + unmapped).get("totalElements").asLong()).isZero();
        assertThat(getJson("admin", "/api/observability/mappings/" + body(rule).get("id").asLong()).get("matchCount").asLong()).isGreaterThanOrEqualTo(2);

        // Validation: invalid regex, no target, unknown service.
        mvc.perform(jsonBody(as("admin", post("/api/observability/mappings")), Map.of("name", "bad", "attributeKey", "service.name", "matchType", "REGEX",
                "matchValue", "([", "serviceId", svc[0]))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("admin", post("/api/observability/mappings")), Map.of("name", "bad", "attributeKey", "service.name", "matchType", "EXACT",
                "matchValue", "x"))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("admin", post("/api/observability/mappings")), Map.of("name", "bad", "attributeKey", "service.name", "matchType", "EXACT",
                "matchValue", "x", "serviceId", 99_999_999))).andExpect(status().isBadRequest());

        // Tracked attribute change: service.version 1.0 → 1.1 on the same resource.
        export("metrics", token, OtlpPayloads.metrics(resource(mapped, "service.version", "1.0"), List.of(new Gauge("m", null, Map.of(), t, 3))));
        export("metrics", token, OtlpPayloads.metrics(resource(mapped, "service.version", "1.1"), List.of(new Gauge("m", null, Map.of(), t.plusSeconds(1), 4))));
        long resourceId = m.get("content").get(0).get("id").asLong();
        JsonNode changes = getJson("admin", "/api/observability/resources/" + resourceId + "/changes");
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("attributeKey").asText()).isEqualTo("service.version");
        assertThat(changes.get(0).get("fromValue").asText()).isEqualTo("1.0");
        assertThat(changes.get(0).get("toValue").asText()).isEqualTo("1.1");
    }

    @Test
    void rollupsAggregateMinutesAndRetentionPurgesOldData() throws Exception {
        String token = otlpSource("OTLP rollup " + uid());
        String service = "rollup-" + uid();
        Instant minute = clock.instant().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.MINUTES);
        List<Gauge> points = List.of(new Gauge("rollup.test", null, Map.of(), minute.plusSeconds(5), 10), new Gauge("rollup.test", null, Map.of(),
                minute.plusSeconds(25), 20), new Gauge("rollup.test", null, Map.of(), minute.plusSeconds(45), 30));
        assertThat(export("metrics", token, OtlpPayloads.metrics(resource(service), points)).getResponse().getStatus()).isEqualTo(200);
        long seriesId = jdbc.queryForObject("select s.id from telemetry_metric_series s join telemetry_resources r on r.id = s.resource_id where "
                + "r.service_name = ?", Long.class, service);
        assertThat(jdbc.queryForObject("select count(*) from telemetry_rollup_queue where bucket_start = ?", Long.class, java.sql.Timestamp.from(minute))).isOne();
        maintenance.processPending(1000);
        Map<String, Object> rollup = jdbc.queryForMap("select sample_count, sum_value, min_value, max_value, avg_value, p95_value from telemetry_metric_rollups "
                + "where series_id = ?", seriesId);
        assertThat(((Number) rollup.get("sample_count")).intValue()).isEqualTo(3);
        assertThat(((Number) rollup.get("avg_value")).doubleValue()).isEqualTo(20.0);
        assertThat(((Number) rollup.get("min_value")).doubleValue()).isEqualTo(10.0);
        assertThat(((Number) rollup.get("max_value")).doubleValue()).isEqualTo(30.0);
        assertThat(((Number) rollup.get("p95_value")).doubleValue()).isEqualTo(29.0);
        // A late point for the same minute re-queues it and the roll-up is recomputed.
        export("metrics", token, OtlpPayloads.metrics(resource(service), List.of(new Gauge("rollup.test", null, Map.of(), minute.plusSeconds(50), 40))));
        JsonNode s = getJson("admin", "/api/observability/metrics/series?name=rollup.test&aggregation=max&step=60&from=" + minute.minusSeconds(60) + "&to="
                + minute.plusSeconds(120));
        assertThat(findSeries(s, service).get("points").get(0).get("v").asDouble()).isEqualTo(40.0);
        assertThat(findSeries(s, service).get("points").get(0).get("samples").asLong()).isEqualTo(4);
        JsonNode sum = getJson("admin", "/api/observability/metrics/series?name=rollup.test&aggregation=sum&step=60&from=" + minute.minusSeconds(60) + "&to="
                + minute.plusSeconds(120));
        assertThat(findSeries(sum, service).get("points").get(0).get("v").asDouble()).isEqualTo(100.0);
        mvc.perform(as("admin", get("/api/observability/metrics/series?name=rollup.test&aggregation=median"))).andExpect(status().isBadRequest());
        mvc.perform(as("admin", get("/api/observability/metrics/series?name=rollup.test&step=30"))).andExpect(status().isBadRequest());

        JsonNode retention = getJson("admin", "/api/observability/retention");
        assertThat(retention.get("rawDays").asInt()).isEqualTo(7);
        try {
            mvc.perform(jsonBody(as("admin", put("/api/observability/retention")), Map.of("rawDays", 2, "rollupDays", 90, "logsDays", 14, "tracesDays", 7)))
                    .andExpect(status().isOk());
            JsonNode purge = body(mvc.perform(as("admin", post("/api/observability/retention/purge"))).andExpect(status().isOk()).andReturn());
            assertThat(purge.get("points").asLong()).isGreaterThanOrEqualTo(4);
            assertThat(jdbc.queryForObject("select count(*) from telemetry_metric_points where series_id = ?", Long.class, seriesId)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from telemetry_metric_rollups where series_id = ?", Long.class, seriesId)).isOne();
            mvc.perform(jsonBody(as("admin", put("/api/observability/retention")), Map.of("rawDays", 2, "rollupDays", 2, "logsDays", 14, "tracesDays", 7)))
                    .andExpect(status().isOk());
            maintenance.purge();
            assertThat(jdbc.queryForObject("select count(*) from telemetry_metric_rollups where series_id = ?", Long.class, seriesId)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from telemetry_metric_series where id = ?", Long.class, seriesId)).isZero();
            mvc.perform(jsonBody(as("admin", put("/api/observability/retention")), Map.of("rawDays", 10, "rollupDays", 5, "logsDays", 14, "tracesDays", 7)))
                    .andExpect(status().isBadRequest());
        } finally {
            mvc.perform(jsonBody(as("admin", put("/api/observability/retention")), Map.of("rawDays", 7, "rollupDays", 90, "logsDays", 14, "tracesDays", 7)))
                    .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'TELEMETRY_RETENTION_PURGED'", Long.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void oldAndFutureTimestampsAreRejected() throws Exception {
        String token = otlpSource("OTLP range " + uid());
        Instant now = clock.instant();
        ObjectNode body = OtlpPayloads.metrics(resource("range-" + uid()), List.of(new Gauge("r", null, Map.of(), now.minus(Duration.ofDays(30)), 1),
                new Gauge("r", null, Map.of(), now.plus(Duration.ofHours(2)), 1), new Gauge("r", null, Map.of(), now, 1)));
        JsonNode r = body(export("metrics", token, body));
        assertThat(r.at("/partialSuccess/rejectedDataPoints").asInt()).isEqualTo(2);
        Map<String, Object> rotated = Map.of();
        JsonNode sources = getJson("admin", "/api/observability/sources");
        long id = 0;
        for (JsonNode s : sources) {
            if (token.startsWith(s.get("tokenHint").asText(""))) {
                id = s.get("id").asLong();
            }
        }
        JsonNode rotation = body(mvc.perform(as("admin", post("/api/observability/sources/" + id + "/rotate-token"))).andExpect(status().isOk()).andReturn());
        String fresh = rotation.get("token").asText();
        assertThat(fresh).startsWith("otsec_").isNotEqualTo(token);
        assertThat(export("metrics", token, body).getResponse().getStatus()).isEqualTo(401);
        assertThat(export("metrics", fresh, body).getResponse().getStatus()).isEqualTo(200);
        assertThat(rotated).isEmpty();
    }

    private static JsonNode findSeries(JsonNode response, String serviceName) {
        for (JsonNode s : response.get("series")) {
            if (serviceName.equals(s.get("resourceServiceName").asText())) {
                return s;
            }
        }
        throw new AssertionError("No series for " + serviceName + " in " + response);
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}

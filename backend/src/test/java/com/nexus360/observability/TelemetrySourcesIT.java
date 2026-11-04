package com.nexus360.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pull sources: the Prometheus HTTP API adapter against a local stub server (test call, bearer credential, pull into SA series, wrong
 * credentials), secrets never returned, and the prepared adapters (CloudWatch, Azure Monitor, GCP, Grafana) staying NOT_CONNECTED.
 */
class TelemetrySourcesIT extends ObservabilityTestSupport {

    private HttpServer server;
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(auth == null ? "" : auth);
            paths.add(exchange.getRequestURI().toString());
            String body;
            int code = 200;
            if (!"Bearer prom-secret-123".equals(auth)) {
                code = 401;
                body = "{\"status\":\"error\",\"errorType\":\"unauthorized\",\"error\":\"bad token\"}";
            } else if (exchange.getRequestURI().getPath().equals("/api/v1/status/buildinfo")) {
                body = "{\"status\":\"success\",\"data\":{\"version\":\"2.53.0\"}}";
            } else if (exchange.getRequestURI().getPath().equals("/api/v1/query_range")) {
                long now = System.currentTimeMillis() / 1000;
                body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"__name__\":\"up\",\"job\":\"prom-stub-svc\","
                        + "\"instance\":\"node-1:9100\",\"zone\":\"a\"},\"values\":[[" + (now - 120) + ",\"1\"],[" + (now - 60) + ",\"0\"],[" + now
                        + ",\"NaN\"]]}]}}";
            } else {
                code = 404;
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void prometheusSourceIsVerifiedAndPulledThroughTheRealClient() throws Exception {
        JsonNode created = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/observability/sources")), Map.of("name", "Prometheus stub " + uid(), "kind",
                "PROMETHEUS_API", "baseUrl", base(), "authType", "BEARER", "secret", "prom-secret-123", "config", Map.of("queries", List.of(Map.of("name",
                        "prom.up", "query", "up{job=\"prom-stub-svc\"}"))), "pullEnabled", true, "pullIntervalSeconds", 60))).andExpect(status().isCreated())
                .andReturn());
        assertThat(created.get("token").isNull()).isTrue();
        JsonNode source = created.get("source");
        long id = source.get("id").asLong();
        assertThat(source.get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(source.get("hasSecret").asBoolean()).isTrue();
        assertThat(source.get("prepared").asBoolean()).isFalse();
        assertThat(created.toString()).doesNotContain("prom-secret-123");
        assertThat(jdbc.queryForObject("select secret_encrypted from telemetry_sources where id = ?", String.class, id)).startsWith("v1:")
                .doesNotContain("prom-secret");

        JsonNode test = body(mvc.perform(as("rajesh.iyer", post("/api/observability/sources/" + id + "/test"))).andExpect(status().isOk()).andReturn());
        assertThat(test.get("success").asBoolean()).isTrue();
        assertThat(test.get("status").asText()).isEqualTo("CONNECTED");
        assertThat(test.get("lastVerifiedAt").isNull()).isFalse();
        assertThat(paths).contains("/api/v1/status/buildinfo");
        assertThat(authHeaders).contains("Bearer prom-secret-123");

        JsonNode pull = body(mvc.perform(as("rajesh.iyer", post("/api/observability/sources/" + id + "/pull?lookbackMinutes=5"))).andExpect(status().isOk())
                .andReturn());
        assertThat(pull.get("series").asInt()).isEqualTo(1);
        assertThat(pull.get("points").asInt()).isEqualTo(2);
        assertThat(paths.stream().anyMatch(p -> p.startsWith("/api/v1/query_range?query=up%7Bjob%3D%22prom-stub-svc%22%7D&start="))).isTrue();
        JsonNode resource = getJson("admin", "/api/observability/resources?search=prom-stub-svc").get("content").get(0);
        assertThat(resource.get("hostName").asText()).isEqualTo("node-1");
        assertThat(resource.get("sourceId").asLong()).isEqualTo(id);
        JsonNode series = getJson("admin", "/api/observability/metrics/series?name=prom.up&attr.zone=a");
        assertThat(series.get("series").get(0).get("points")).hasSize(2);
        assertThat(sourceTestIsAudited()).isTrue();

        // Wrong credential after a change: re-verification fails and the source is not connected.
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/observability/sources/" + id)), Map.of("name", "Prometheus stub " + uid(), "kind", "PROMETHEUS_API",
                "baseUrl", base(), "authType", "BEARER", "secret", "wrong", "config", Map.of("queries", List.of(Map.of("name", "prom.up", "query", "up"))))))
                .andExpect(status().isOk());
        JsonNode failed = body(mvc.perform(as("rajesh.iyer", post("/api/observability/sources/" + id + "/test"))).andExpect(status().isOk()).andReturn());
        assertThat(failed.get("success").asBoolean()).isFalse();
        assertThat(failed.get("httpStatus").asInt()).isEqualTo(401);
        assertThat(failed.get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(sources().toString()).doesNotContain("wrong").doesNotContain("prom-secret");

        // Configuration is validated.
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/observability/sources")), Map.of("name", "Bad prom " + uid(), "kind", "PROMETHEUS_API", "baseUrl",
                base(), "config", Map.of()))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/observability/sources")), Map.of("name", "Bad url " + uid(), "kind", "PROMETHEUS_API", "baseUrl",
                "ftp://x", "config", Map.of("queries", List.of(Map.of("name", "a", "query", "up")))))).andExpect(status().isBadRequest());
    }

    @Test
    void preparedAdaptersStayNotConnected() throws Exception {
        JsonNode cw = body(mvc.perform(jsonBody(as("admin", post("/api/observability/sources")), Map.of("name", "CloudWatch " + uid(), "kind", "CLOUDWATCH",
                "authType", "API_KEY", "secret", "AKIA-not-real", "config", Map.of("region", "ap-south-1", "queries", List.of(Map.of("name", "aws.cpu",
                        "namespace", "AWS/EC2", "metricName", "CPUUtilization", "dimensions", Map.of("InstanceId", "i-123"))))))).andExpect(status().isCreated())
                .andReturn()).get("source");
        assertThat(cw.get("prepared").asBoolean()).isTrue();
        assertThat(cw.get("status").asText()).isEqualTo("NOT_CONNECTED");
        JsonNode test = body(mvc.perform(as("admin", post("/api/observability/sources/" + cw.get("id").asLong() + "/test"))).andExpect(status().isOk())
                .andReturn());
        assertThat(test.get("success").asBoolean()).isFalse();
        assertThat(test.get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(test.get("message").asText()).contains("Signature Version 4");
        mvc.perform(as("admin", post("/api/observability/sources/" + cw.get("id").asLong() + "/pull"))).andExpect(status().isConflict());

        JsonNode azure = body(mvc.perform(jsonBody(as("admin", post("/api/observability/sources")), Map.of("name", "Azure " + uid(), "kind", "AZURE_MONITOR",
                "config", Map.of("resourceId", "/subscriptions/0000/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/vm1", "queries",
                        List.of(Map.of("name", "azure.cpu", "metricName", "Percentage CPU")))))).andExpect(status().isCreated()).andReturn()).get("source");
        assertThat(azure.get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(azure.get("prepared").asBoolean()).isTrue();
        JsonNode gcp = body(mvc.perform(jsonBody(as("admin", post("/api/observability/sources")), Map.of("name", "GCP " + uid(), "kind", "GCP_MONITORING",
                "config", Map.of("projectId", "sa-demo-project", "queries", List.of(Map.of("name", "gcp.cpu", "filter",
                        "metric.type=\"compute.googleapis.com/instance/cpu/utilization\"")))))).andExpect(status().isCreated()).andReturn()).get("source");
        assertThat(gcp.get("status").asText()).isEqualTo("NOT_CONNECTED");
        // Prepared adapters cannot be scheduled for pulls.
        mvc.perform(jsonBody(as("admin", post("/api/observability/sources")), Map.of("name", "Grafana " + uid(), "kind", "GRAFANA", "baseUrl", base(),
                "pullEnabled", true, "config", Map.of("datasourceUid", "prom1", "queries", List.of(Map.of("name", "g.up", "expr", "up"))))))
                .andExpect(status().isBadRequest());
        // Grafana test call against the stub: wrong token → still NOT_CONNECTED.
        JsonNode grafana = body(mvc.perform(jsonBody(as("admin", post("/api/observability/sources")), Map.of("name", "Grafana " + uid(), "kind", "GRAFANA",
                "baseUrl", base(), "authType", "BEARER", "secret", "glsa_wrong", "config", Map.of("datasourceUid", "prom1", "queries",
                        List.of(Map.of("name", "g.up", "expr", "up")))))).andExpect(status().isCreated()).andReturn()).get("source");
        JsonNode gtest = body(mvc.perform(as("admin", post("/api/observability/sources/" + grafana.get("id").asLong() + "/test"))).andExpect(status().isOk())
                .andReturn());
        assertThat(gtest.get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(paths).contains("/api/datasources/uid/prom1");
        JsonNode kinds = getJson("ananya.bose", "/api/observability/sources/kinds");
        assertThat(kinds).hasSize(6);
        assertThat(kinds.toString()).contains("\"kind\":\"CLOUDWATCH\",\"prepared\":true,\"pullSupported\":false,\"testSupported\":false");
    }

    private JsonNode sources() throws Exception {
        return getJson("admin", "/api/observability/sources");
    }

    private boolean sourceTestIsAudited() {
        Long n = jdbc.queryForObject("select count(*) from audit_logs where action = 'TELEMETRY_SOURCE_TESTED'", Long.class);
        return n != null && n > 0;
    }
}

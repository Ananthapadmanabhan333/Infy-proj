package com.nexus360.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** Helpers shared by the Release 4 integration tests: OTLP sources and exports, CMDB fixtures. */
public abstract class ObservabilityTestSupport extends IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    /** Creates an OTLP source as the system administrator and returns its token. */
    protected String otlpSource(String name) throws Exception {
        MvcResult r = mvc.perform(jsonBody(as("admin", post("/api/observability/sources")), Map.of("name", name, "kind", "OTLP"))).andReturn();
        if (r.getResponse().getStatus() != 201) {
            throw new IllegalStateException(r.getResponse().getContentAsString());
        }
        return body(r).get("token").asText();
    }

    protected MvcResult export(String signal, String token, Object body) throws Exception {
        return mvc.perform(post("/api/otel/v1/" + signal).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : json.writeValueAsString(body))).andReturn();
    }

    protected static Map<String, String> resource(String serviceName, String... extra) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("service.name", serviceName);
        r.put("deployment.environment", "test");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            r.put(extra[i], extra[i + 1]);
        }
        return r;
    }

    protected static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Gauge points of one series, one per minute ending one minute before {@code end}. */
    protected static List<OtlpPayloads.Gauge> gauges(String metric, Map<String, String> attributes, Instant end, double[] values) {
        java.util.ArrayList<OtlpPayloads.Gauge> out = new java.util.ArrayList<>();
        Instant start = end.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).minusSeconds(60L * values.length);
        for (int i = 0; i < values.length; i++) {
            out.add(new OtlpPayloads.Gauge(metric, "ms", attributes, start.plusSeconds(60L * i + 10), values[i]));
        }
        return out;
    }

    protected long ci(String name, String type) throws Exception {
        MvcResult r = mvc.perform(jsonBody(as("admin", post("/api/cmdb/cis")), Map.of("name", name, "ciType", type, "environment", "PRODUCTION"))).andReturn();
        if (r.getResponse().getStatus() != 201) {
            throw new IllegalStateException(r.getResponse().getContentAsString());
        }
        return body(r).get("id").asLong();
    }

    protected long relationship(long source, long target, String type) throws Exception {
        MvcResult r = mvc.perform(jsonBody(as("admin", post("/api/cmdb/relationships")), Map.of("sourceId", source, "targetId", target, "type", type)))
                .andReturn();
        if (r.getResponse().getStatus() != 201) {
            throw new IllegalStateException(r.getResponse().getContentAsString());
        }
        JsonNode b = body(r);
        return b.has("id") ? b.get("id").asLong() : jdbc.queryForObject("select id from ci_relationships where source_id = ? and target_id = ? and rel_type = ?",
                Long.class, source, target, type);
    }

    /** Creates a business service (and, through ServiceCiSync, its BUSINESS_SERVICE CI); returns {serviceId, ciId}. */
    protected long[] service(String code) throws Exception {
        long dept = jdbc.queryForObject("select id from departments where code = 'IT-APPS'", Long.class);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("code", code);
        req.put("name", "Service " + code);
        req.put("departmentId", dept);
        req.put("tier", 2);
        MvcResult r = mvc.perform(jsonBody(as("admin", post("/api/services")), req)).andReturn();
        if (r.getResponse().getStatus() != 201 && r.getResponse().getStatus() != 200) {
            throw new IllegalStateException(r.getResponse().getContentAsString());
        }
        long id = body(r).get("id").asLong();
        long ciId = jdbc.queryForObject("select id from configuration_items where service_id = ?", Long.class, id);
        return new long[] {id, ciId};
    }
}

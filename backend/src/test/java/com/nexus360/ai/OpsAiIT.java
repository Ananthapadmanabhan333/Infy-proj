package com.nexus360.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Release 3 SA AI features without an LLM provider (the test configuration): every answer is the deterministic engine's, labelled
 * FALLBACK with the reason; SLA risk is a computed probability; calls count toward the per-user AI rate limit.
 */
class OpsAiIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private static void assertFallback(JsonNode result) {
        assertThat(result.get("source").asText()).isEqualTo("FALLBACK");
        assertThat(result.get("fallbackReason").asText()).isEqualTo("No AI provider configured");
        assertThat(result.get("notice").asText()).contains("deterministic engine");
    }

    private JsonNode call(String user, String path) throws Exception {
        return body(mvc.perform(as(user, post(path))).andExpect(status().isOk()).andReturn());
    }

    @Test
    void problemCorrelationCmdbImpactRunbooksAndTrendsAreLabelledDeterministicAnswers() throws Exception {
        long problem = jdbc.queryForObject("select id from problems where is_demo and status = 'KNOWN_ERROR' order by id limit 1", Long.class);
        JsonNode correlation = call("riya.varma", "/api/ai/problems/" + problem + "/correlation");
        assertFallback(correlation);
        assertThat(correlation.at("/data/basis").asText()).startsWith("Deterministic");
        assertThat(correlation.at("/data/summary").asText()).isNotBlank();
        for (JsonNode c : correlation.at("/data/candidates")) {
            assertThat(c.get("score").asDouble()).isBetween(0.3, 1.0);
            assertThat(c.get("reasons")).isNotEmpty();
        }

        long ci = jdbc.queryForObject("select id from configuration_items where name = 'payroll-db-primary'", Long.class);
        JsonNode impact = call("ananya.bose", "/api/ai/cmdb/cis/" + ci + "/impact");
        assertFallback(impact);
        assertThat(impact.at("/data/impactedServices").findValuesAsText("code")).contains("PAYROLL");
        assertThat(impact.at("/data/riskLevel").asText()).isEqualTo("HIGH"); // tier-1 Payroll depends on the database
        assertThat(impact.at("/data/recommendations")).isNotEmpty();
        impact.at("/data/recommendations").forEach(r -> assertThat(GuardedLlm.containsCommand(r.asText())).isFalse());

        long payroll = jdbc.queryForObject("select id from business_services where code = 'PAYROLL'", Long.class);
        long incident = createIncident("riya.varma", Map.of("title", unique("Payroll portal outage with 503 errors"), "description",
                unique("Payroll application nodes return 503"), "serviceId", payroll, "category", "APPLICATION")).at("/incident/id").asLong();
        JsonNode runbooks = call("riya.varma", "/api/ai/incidents/" + incident + "/runbooks");
        assertFallback(runbooks);
        assertThat(runbooks.at("/data/recommendations/0/title").asText()).isEqualTo("Payroll application outage recovery");
        assertThat(runbooks.at("/data/recommendations/0/reasons").toString()).contains("Written for the incident's service");
        assertThat(runbooks.at("/data/recommendations/0/score").asInt()).isGreaterThanOrEqualTo(40);
        assertThat(runbooks.at("/data/recommendations/0/requiresApproval").asBoolean()).isTrue();

        JsonNode trends = call("priya.raman", "/api/ai/trends?days=28");
        assertFallback(trends);
        assertThat(trends.at("/data/days").asInt()).isEqualTo(28);
        assertThat(trends.at("/data/trends")).isNotEmpty();
        assertThat(trends.at("/data/narrative").asText()).isNotBlank();
        for (JsonNode t : trends.at("/data/trends")) {
            if (t.get("zScore").isNumber() && t.get("significant").asBoolean()) {
                assertThat(Math.abs(t.get("zScore").asDouble())).isGreaterThanOrEqualTo(2.0);
            }
        }

        // Interactions are recorded with their source; employees have no access.
        assertThat(jdbc.queryForObject("select count(*) from ai_interactions where feature = 'cmdb_impact' and source = 'FALLBACK'", Long.class)).isPositive();
        mvc.perform(as("kavya.reddy", post("/api/ai/trends"))).andExpect(status().isForbidden());
        mvc.perform(as("ananya.bose", post("/api/ai/incidents/" + incident + "/runbooks"))).andExpect(status().isForbidden());
    }

    @Test
    void slaRiskIsAComputedProbabilityFromTheElapsedShareAndHistoricalClocks() throws Exception {
        long incident = createIncident("riya.varma", Map.of("title", unique("Quasar print spooler stalls"), "description", unique("Spooler stalls"),
                "impact", "MEDIUM", "urgency", "MEDIUM")).at("/incident/id").asLong();
        Map<String, Object> sla = jdbc.queryForMap("select priority, resolution_target_minutes from incident_slas where incident_id = ?", incident);
        int target = ((Number) sla.get("resolution_target_minutes")).intValue();
        // Half of the resolution target is consumed (the clock keeps running from now).
        jdbc.update("update incident_slas set consumed_seconds = ?, running_since = now(), clock_status = 'RUNNING', paused_at = null where incident_id = ?",
                target * 30L, incident);
        JsonNode r = call("riya.varma", "/api/ai/incidents/" + incident + "/sla-risk");
        assertFallback(r);
        JsonNode d = r.get("data");
        assertThat(d.get("elapsedPercent").asDouble()).isBetween(49.9, 52.0);
        // Hand computation: comparable completed clocks of the same priority (180 days) still running at half of their target.
        List<Map<String, Object>> history = jdbc.queryForList("select consumed_seconds / (resolution_target_minutes * 60.0) as share, outcome "
                + "from incident_slas where priority = ? and outcome in ('MET', 'MISSED') and completed_at >= now() - interval '180 days' and incident_id <> ?",
                sla.get("priority"), incident);
        double e = d.get("elapsedPercent").asDouble() / 100;
        long sample = history.stream().filter(h -> ((Number) h.get("share")).doubleValue() > e).count();
        long breaches = history.stream().filter(h -> ((Number) h.get("share")).doubleValue() > e && "MISSED".equals(h.get("outcome"))).count();
        double expected = sample >= 5 ? (double) breaches / sample : (breaches + 2 * e) / (sample + 2.0);
        assertThat(d.get("sampleSize").asLong()).isEqualTo(sample);
        assertThat(d.get("historicalBreaches").asLong()).isEqualTo(breaches);
        assertThat(d.get("probability").asDouble()).isCloseTo(Math.round(expected * 100) / 100.0, org.assertj.core.data.Offset.offset(0.011));
        assertThat(d.get("probabilityLabel").asText()).contains("Computed probability").contains("not an AI estimate");
        assertThat(d.get("riskLevel").asText()).isIn("LOW", "MEDIUM", "HIGH");
        assertThat(d.get("method").asText()).startsWith("Empirical conditional breach rate");

        // Past the target: probability 1.
        jdbc.update("update incident_slas set consumed_seconds = ? where incident_id = ?", target * 120L, incident);
        JsonNode breached = call("riya.varma", "/api/ai/incidents/" + incident + "/sla-risk").get("data");
        assertThat(breached.get("probability").asDouble()).isEqualTo(1.0);
        assertThat(breached.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(breached.get("recommendations")).isNotEmpty();

        // The ranking of active incidents is statistics (analytics), sorted by probability.
        JsonNode ranking = getJson("sunita.joshi", "/api/analytics/sla-risk?limit=20");
        double previous = 2;
        for (JsonNode x : ranking.get("incidents")) {
            assertThat(x.get("probability").asDouble()).isLessThanOrEqualTo(previous);
            previous = x.get("probability").asDouble();
        }
        assertThat(ranking.get("incidents").findValuesAsText("incidentId")).contains(String.valueOf(incident));
        mvc.perform(as("kavya.reddy", get("/api/analytics/sla-risk"))).andExpect(status().isForbidden());
    }

    @Test
    void releaseThreeAiCallsAreRateLimitedPerUser() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(as("pooja.iyer", post("/api/ai/problems/999999999/correlation"))).andExpect(status().isNotFound());
        }
        mvc.perform(as("pooja.iyer", post("/api/ai/trends"))).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mvc.perform(as("vishal.kumar", post("/api/ai/trends"))).andExpect(status().isOk());
    }
}

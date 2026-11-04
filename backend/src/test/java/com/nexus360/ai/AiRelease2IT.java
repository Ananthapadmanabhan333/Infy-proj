package com.nexus360.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.config.AppProperties;
import com.nexus360.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** SA AI Release 2: resolution advice, workflow suggestions, insight confidence/hourly series, history and the per-user limit. */
class AiRelease2IT extends IntegrationTest {

    @Autowired
    AppProperties properties;

    @Autowired
    JdbcTemplate jdbc;

    private JsonNode resolution(String user, long incidentId) throws Exception {
        return body(mvc.perform(as(user, post("/api/ai/incidents/" + incidentId + "/resolution"))).andExpect(status().isOk()).andReturn());
    }

    private void resolve(long id) throws Exception {
        mvc.perform(jsonBody(as("arjun.kapoor", post("/api/incidents/" + id + "/transition")), Map.of("status", "RESOLVED",
                "resolutionCode", "FIXED", "resolutionNotes", "Resolved after the AI test.")));
    }

    @Test
    void resolutionAdviceFallbackIsComputedFromMatchedEvidence() throws Exception {
        // Same service and category as resolved VPN incidents, but worded differently so it is not auto-linked as a duplicate.
        long vpn = createIncident("ishaan.malhotra", Map.of("title", unique("VPN client keeps reconnecting in hotel rooms"),
                "description", "Client resets the secure link while I am travelling")).at("/incident/id").asLong();
        long odd = createIncident("ishaan.malhotra", Map.of("title", "Zyxwv quorbl flange " + UUID.randomUUID().toString().substring(0, 8),
                "description", "Plovic snarf of the trellimax", "category", "FACILITIES")).at("/incident/id").asLong();
        try {
            JsonNode advice = resolution("arjun.kapoor", vpn);
            assertThat(advice.get("source").asText()).isEqualTo("FALLBACK");
            assertThat(advice.get("notice").asText()).contains("No LLM was used");
            JsonNode data = advice.get("data");
            assertThat(data.get("likelyCause").asText()).isNotBlank();
            assertThat(data.get("steps").size()).isPositive();
            assertThat(data.at("/runbook/title").asText()).isEqualTo("VPN gateway failover");
            assertThat(data.at("/runbook/requiresApproval").asBoolean()).isTrue();
            assertThat(data.get("articles").findValuesAsText("title")).contains("VPN connection troubleshooting");
            assertThat(data.get("matchedIncidents").size()).isPositive();
            assertThat(data.get("basis").asText()).startsWith("Based on");
            int vpnConfidence = data.get("confidence").asInt();
            assertThat(vpnConfidence).isBetween(1, 100);

            JsonNode weak = resolution("arjun.kapoor", odd).get("data");
            int weakConfidence = weak.get("confidence").asInt();
            assertThat(weakConfidence).isBetween(0, 100).isLessThan(vpnConfidence); // derived from evidence, not a constant
            assertThat(weak.get("matchedIncidents")).isEmpty();

            mvc.perform(as("amit.shah", post("/api/ai/incidents/" + vpn + "/resolution"))).andExpect(status().isForbidden());

            JsonNode recent = getJson("arjun.kapoor", "/api/ai/interactions?size=5");
            assertThat(recent.size()).isBetween(1, 5);
            assertThat(recent.get(0).get("feature").asText()).isEqualTo("resolution");
            assertThat(recent.get(0).get("source").asText()).isEqualTo("FALLBACK");
            assertThat(recent.get(0).get("subject").asText()).startsWith("INC-");
            assertThat(getJson("ishaan.malhotra", "/api/ai/interactions").findValuesAsText("feature")).doesNotContain("resolution");
        } finally {
            resolve(vpn);
            resolve(odd);
        }
    }

    @Test
    void workflowSuggestionsAreValidatedAndNeverSaved() throws Exception {
        long workflowsBefore = jdbc.queryForObject("select count(*) from workflows", Long.class);
        String goal = "When a critical payroll incident is created, notify the incident managers and escalate it";
        JsonNode result = body(mvc.perform(jsonBody(as("priya.raman", post("/api/ai/workflow-suggestion")), Map.of("goal", goal)))
                .andExpect(status().isOk()).andReturn());
        assertThat(result.get("source").asText()).isEqualTo("FALLBACK");
        JsonNode s = result.get("data");
        assertThat(s.get("triggerEvent").asText()).isEqualTo("INCIDENT_CREATED");
        assertThat(s.at("/definition/conditions/rules").toString()).contains("PAYROLL").contains("CRITICAL");
        assertThat(s.at("/definition/actions").findValuesAsText("type")).contains("NOTIFY_ROLE", "ESCALATE");
        assertThat(s.at("/definition/actions").toString()).contains("INCIDENT_MANAGER");
        assertThat(s.get("explanation").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from workflows", Long.class)).isEqualTo(workflowsBefore);

        Map<String, Object> wf = new HashMap<>(Map.of("name", s.get("name").asText() + " " + UUID.randomUUID().toString().substring(0, 6),
                "triggerEvent", s.get("triggerEvent").asText(), "active", false, "maxExecutionsPerHour", 10, "maxRetries", 0,
                "retryBackoffSeconds", 5, "definition", json.convertValue(s.get("definition"), Map.class)));
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), wf)).andExpect(status().isCreated());

        JsonNode scheduled = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/ai/workflow-suggestion")),
                Map.of("goal", "Every 30 minutes remind the assignee about stale pending incidents"))).andExpect(status().isOk()).andReturn());
        assertThat(scheduled.at("/data/triggerEvent").asText()).isEqualTo("SCHEDULED");
        assertThat(scheduled.at("/data/definition/schedule/intervalMinutes").asInt()).isEqualTo(30);

        mvc.perform(jsonBody(as("riya.varma", post("/api/ai/workflow-suggestion")), Map.of("goal", goal))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/ai/workflow-suggestion")), Map.of("goal", goal))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("priya.raman", post("/api/ai/workflow-suggestion")), Map.of("goal", "x"))).andExpect(status().isBadRequest());
    }

    @Test
    void insightsCarryConfidenceAndAnHourlySeries() throws Exception {
        JsonNode insights = body(mvc.perform(as("kiran.rao", post("/api/ai/insights"))).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(insights.get("hourly")).hasSize(24);
        long total = 0;
        for (int h = 0; h < 24; h++) {
            assertThat(insights.at("/hourly/" + h + "/hour").asInt()).isEqualTo(h);
            total += insights.at("/hourly/" + h + "/count").asLong();
        }
        assertThat(total).isPositive();
        for (JsonNode i : insights.get("insights")) {
            assertThat(i.get("confidence").asText()).isIn("HIGH", "MEDIUM", "LOW");
        }
    }

    @Test
    void insightsAreSharedWhileFreshAndRegeneratedOnRequest() throws Exception {
        mvc.perform(as("kiran.rao", post("/api/ai/insights?refresh=true"))).andExpect(status().isOk());
        long before = jdbc.queryForObject("select count(*) from ai_interactions where feature = 'insights'", Long.class);
        mvc.perform(as("priya.raman", post("/api/ai/insights"))).andExpect(status().isOk());
        mvc.perform(as("ananya.bose", post("/api/ai/insights"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ai_interactions where feature = 'insights'", Long.class)).isEqualTo(before);
        mvc.perform(as("kiran.rao", post("/api/ai/insights?refresh=true"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ai_interactions where feature = 'insights'", Long.class)).isEqualTo(before + 1);
    }

    @Test
    void aiCallsAreRateLimitedPerUser() throws Exception {
        int limit = properties.rateLimit().aiPerMinute();
        assertThat(limit).isEqualTo(30);
        for (int i = 0; i < limit; i++) {
            mvc.perform(jsonBody(as("vikram.singh", post("/api/ai/ask")), Map.of())).andExpect(status().isBadRequest());
        }
        mvc.perform(jsonBody(as("vikram.singh", post("/api/ai/ask")), Map.of("question", "How many incidents are open?")))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        // The budget is per user: another user is unaffected.
        mvc.perform(jsonBody(as("meera.sharma", post("/api/ai/ask")), Map.of("question", "How many incidents are open?")))
                .andExpect(status().isOk());
    }
}

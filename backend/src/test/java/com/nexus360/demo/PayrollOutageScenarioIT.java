package com.nexus360.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.sla.SlaEngine;
import com.nexus360.support.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The acceptance scenario: several employees report the same payroll outage and SA detects the duplicates,
 * creates a parent, prioritises, assigns, monitors the SLA, recommends knowledge, escalates, resolves and updates analytics.
 */
class PayrollOutageScenarioIT extends IntegrationTest {

    @Autowired
    SlaEngine slaEngine;

    @Test
    void payrollOutageEndToEnd() throws Exception {
        long resolvedBefore = getJson("priya.raman", "/api/analytics/dashboard?days=7").at("/kpis/resolvedInPeriod").asLong();

        JsonNode scenario = body(mvc.perform(as("admin", post("/api/admin/demo/payroll-outage"))).andExpect(status().isOk()).andReturn());
        long parentId = scenario.get("parentIncidentId").asLong();
        assertThat(parentId).isPositive();
        assertThat(scenario.get("childNumbers").size()).isGreaterThanOrEqualTo(4);
        assertThat(scenario.get("steps").findValuesAsText("title"))
                .contains("Duplicate reports detected", "Priority calculated", "Department assigned", "SLA monitoring started",
                        "Troubleshooting recommended");

        // 1-5: duplicates grouped under a parent, priority raised to P1, major, routed to the payroll owners.
        JsonNode parent = getJson("priya.raman", "/api/incidents/" + parentId);
        assertThat(parent.get("priority").asText()).isEqualTo("CRITICAL");
        assertThat(parent.get("major").asBoolean()).isTrue();
        assertThat(parent.get("serviceName").asText()).isEqualTo("Payroll");
        assertThat(parent.get("departmentName").asText()).isEqualTo("Enterprise Applications");
        assertThat(parent.get("assigneeName").isNull()).isFalse();
        parent.get("children").forEach(c -> assertThat(c.get("parentId").asLong()).isEqualTo(parentId));

        // 6: SLA monitoring on the P1 policy.
        assertThat(parent.at("/sla/policyName").asText()).startsWith("P1");
        assertThat(parent.at("/sla/clockStatus").asText()).isEqualTo("RUNNING");

        // 7: troubleshooting recommendations (deterministic engine, clearly labelled).
        JsonNode kb = body(mvc.perform(as("riya.varma", post("/api/ai/incidents/" + parentId + "/knowledge"))).andReturn());
        assertThat(kb.get("source").asText()).isEqualTo("FALLBACK");
        assertThat(kb.at("/data/articles/0/title").asText()).isEqualTo("Payroll application troubleshooting guide");

        // 8: escalation once the response target passes without a response.
        testClock().advance(Duration.ofMinutes(20));
        slaEngine.evaluate();
        JsonNode sla = getJson("priya.raman", "/api/incidents/" + parentId + "/sla");
        assertThat(sla.get("responseBreached").asBoolean()).isTrue();
        assertThat(sla.get("escalationLevel").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(sla.get("events").findValuesAsText("eventType")).contains("BREACHED");
        testClock().reset();

        // 9: resolution closes every linked report and analytics update from the stored records.
        String agent = parent.get("assigneeName").asText().toLowerCase().replace(' ', '.');
        mvc.perform(jsonBody(as(agent, post("/api/incidents/" + parentId + "/transition")), Map.of("status", "IN_PROGRESS"))).andExpect(status().isOk());
        JsonNode resolved = body(mvc.perform(jsonBody(as(agent, post("/api/incidents/" + parentId + "/transition")), Map.of(
                "status", "RESOLVED", "resolutionCode", "FIXED", "resolutionNotes", "Restarted payroll nodes and raised the DB pool limit.",
                "resolutionArticleId", kb.at("/data/articles/0/id").asLong()))).andExpect(status().isOk()).andReturn());
        resolved.get("children").forEach(c -> assertThat(c.get("status").asText()).isEqualTo("RESOLVED"));
        assertThat(resolved.at("/resolutionArticle/title").asText()).isEqualTo("Payroll application troubleshooting guide");

        long resolvedAfter = getJson("priya.raman", "/api/analytics/dashboard?days=7").at("/kpis/resolvedInPeriod").asLong();
        assertThat(resolvedAfter).isEqualTo(resolvedBefore + 1);

        JsonNode reporterNotifications = getJson("amit.shah", "/api/notifications?size=20");
        assertThat(reporterNotifications.at("/content").findValuesAsText("type")).contains("INCIDENT_RESOLVED");
    }
}

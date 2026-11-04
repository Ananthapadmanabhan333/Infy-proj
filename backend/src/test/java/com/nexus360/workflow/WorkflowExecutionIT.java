package com.nexus360.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkflowExecutionIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private Map<String, Object> workflow(String name, String marker, List<Map<String, Object>> actions, int maxPerHour, int retries) {
        return Map.of("name", name, "description", "test", "triggerEvent", "INCIDENT_CREATED", "active", true,
                "maxExecutionsPerHour", maxPerHour, "maxRetries", retries, "retryBackoffSeconds", 1,
                "definition", Map.of("conditions", Map.of("match", "ALL", "rules", List.of(Map.of("field", "title", "operator", "CONTAINS", "value", marker))),
                        "actions", actions));
    }

    @Test
    void matchingWorkflowRunsItsActions() throws Exception {
        String marker = "wfmark" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode wf = body(mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), workflow("Test add comment " + marker, marker,
                List.of(Map.of("type", "ADD_COMMENT", "params", Map.of("message", "Automated note for {{number}}", "internal", "true")),
                        Map.of("type", "SET_PRIORITY", "params", Map.of("priority", "HIGH"))), 10, 0))).andExpect(status().isCreated()).andReturn());
        long workflowId = wf.get("id").asLong();

        long incidentId = createIncident("divya.pillai", Map.of("title", "Treasury dashboard " + marker + " chart blank",
                "description", "Chart area is empty", "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();

        await(() -> count("select count(*) from workflow_executions where workflow_id = ? and status = 'SUCCEEDED'", workflowId) == 1,
                "workflow execution to succeed");
        JsonNode incident = getJson("priya.raman", "/api/incidents/" + incidentId);
        assertThat(incident.get("priority").asText()).isEqualTo("HIGH");
        JsonNode comments = getJson("priya.raman", "/api/incidents/" + incidentId + "/comments");
        assertThat(comments.findValuesAsText("body")).anyMatch(b -> b.startsWith("Automated note for INC-"));

        // Non-matching incident does not create an execution.
        createIncident("divya.pillai", Map.of("title", unique("Printer jam on the second floor"), "description", "Unrelated"));
        Thread.sleep(500);
        assertThat(count("select count(*) from workflow_executions where workflow_id = ?", workflowId)).isEqualTo(1);

        // Dry-run test endpoint evaluates rule by rule without side effects.
        mvc.perform(jsonBody(as("riya.varma", post("/api/workflows/" + workflowId + "/test")), Map.of("incidentId", incidentId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.plannedActions[0]").value(org.hamcrest.Matchers.startsWith("Add work note")));
    }

    @Test
    void failingActionIsRetriedThenFailsAndLimitsApply() throws Exception {
        String marker = "wffail" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode integration = body(mvc.perform(jsonBody(as("admin", post("/api/integrations")), Map.of("name", "Dead endpoint " + marker,
                "type", "WEBHOOK", "enabled", true, "endpointUrl", "http://127.0.0.1:9/unreachable", "subscribedEvents", List.of())))
                .andExpect(status().isCreated()).andReturn());
        long integrationId = integration.at("/integration/id").asLong();
        JsonNode wf = body(mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), workflow("Webhook " + marker, marker,
                List.of(Map.of("type", "SEND_WEBHOOK", "params", Map.of("integrationId", String.valueOf(integrationId)))), 1, 1)))
                .andExpect(status().isCreated()).andReturn());
        long workflowId = wf.get("id").asLong();

        createIncident("divya.pillai", Map.of("title", "Ledger sync " + marker + " stalled", "description", "x"));
        await(() -> count("select count(*) from workflow_executions where workflow_id = ? and status = 'RETRY_SCHEDULED'", workflowId) == 1,
                "first attempt to fail and schedule a retry");
        assertThat(jdbc.queryForObject("select error_message from workflow_executions where workflow_id = ?", String.class, workflowId))
                .contains("SEND_WEBHOOK failed on attempt 1");

        // Execution limit: max 1 per hour -> the second matching incident is rate limited.
        createIncident("divya.pillai", Map.of("title", "Ledger sync " + marker + " stalled again", "description", "y"));
        await(() -> count("select count(*) from workflow_executions where workflow_id = ? and status = 'RATE_LIMITED'", workflowId) == 1,
                "second execution to be rate limited");

        // Versioning: editing the definition creates version 2.
        Map<String, Object> edited = new java.util.HashMap<>(workflow("Webhook " + marker, marker,
                List.of(Map.of("type", "NOTIFY_ROLE", "params", Map.of("role", "IT_MANAGER"))), 1, 1));
        edited.put("changeNote", "Replace webhook with notification");
        mvc.perform(jsonBody(as("priya.raman", put("/api/workflows/" + workflowId)), edited))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(getJson("priya.raman", "/api/workflows/" + workflowId + "/versions")).hasSize(2);
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows/" + workflowId + "/rollback")), Map.of("versionNumber", 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentVersion").value(3))
                .andExpect(jsonPath("$.definition.actions[0].type").value("SEND_WEBHOOK"));
    }

    @Test
    void invalidDefinitionsAreRejected() throws Exception {
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), workflow(unique("Bad dept"), "x",
                List.of(Map.of("type", "ASSIGN_DEPARTMENT", "params", Map.of("departmentCode", "NOPE"))), 10, 0)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Unknown department code NOPE"));
        mvc.perform(jsonBody(as("riya.varma", post("/api/workflows")), workflow(unique("Agent cannot"), "x",
                List.of(Map.of("type", "NOTIFY_ROLE", "params", Map.of("role", "IT_MANAGER"))), 10, 0)))
                .andExpect(status().isForbidden());
    }

    private long count(String sql, long id) {
        Long c = jdbc.queryForObject(sql, Long.class, id);
        return c == null ? 0 : c;
    }
}

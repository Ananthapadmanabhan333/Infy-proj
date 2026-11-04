package com.nexus360.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Release 2 workflow engine: else branch, CHANGE_STATUS, PRIORITY_CHANGED, SCHEDULED, templates, run history, failure notices. */
class WorkflowRelease2IT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WorkflowEngine engine;

    private static String marker(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    private static Map<String, Object> definition(List<Map<String, Object>> rules, List<Map<String, Object>> actions,
                                                  List<Map<String, Object>> elseActions) {
        Map<String, Object> d = new HashMap<>();
        d.put("conditions", Map.of("match", "ALL", "rules", rules));
        d.put("actions", actions);
        d.put("elseActions", elseActions);
        return d;
    }

    private static Map<String, Object> workflow(String name, String trigger, Map<String, Object> definition, int retries) {
        return new HashMap<>(Map.of("name", name, "description", "release 2 test", "triggerEvent", trigger, "active", true,
                "maxExecutionsPerHour", 100, "maxRetries", retries, "retryBackoffSeconds", 1, "definition", definition));
    }

    private static Map<String, Object> titleContains(String marker) {
        return Map.of("field", "title", "operator", "CONTAINS", "value", marker);
    }

    private static Map<String, Object> action(String type, Map<String, String> params) {
        return Map.of("type", type, "params", params);
    }

    private long create(Map<String, Object> workflow) throws Exception {
        return body(mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), workflow)).andExpect(status().isCreated()).andReturn())
                .get("id").asLong();
    }

    private void deactivate(long workflowId) throws Exception {
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows/" + workflowId + "/activate")), Map.of("active", false)))
                .andExpect(status().isOk());
    }

    private long count(String sql, Object... args) {
        Long c = jdbc.queryForObject(sql, Long.class, args);
        return c == null ? 0 : c;
    }

    @Test
    void elseActionsRunWhenTheConditionsDoNotMatch() throws Exception {
        String marker = marker("wfelse");
        long wf = create(workflow("Else branch " + marker, "INCIDENT_CREATED", definition(
                List.of(titleContains(marker), Map.of("field", "priority", "operator", "EQUALS", "value", "CRITICAL")),
                List.of(action("ADD_COMMENT", Map.of("message", "Yes branch for {{number}}", "internal", "true"))),
                List.of(action("ADD_COMMENT", Map.of("message", "Else branch for {{number}}", "internal", "true")))), 0));
        try {
            long incident = createIncident("divya.pillai", Map.of("title", "Treasury export " + marker + " missing column",
                    "description", "Column absent", "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();
            await(() -> count("select count(*) from workflow_executions where workflow_id = ? and incident_id = ? and status = 'SUCCEEDED' "
                    + "and branch = 'NO'", wf, incident) == 1, "else branch to run");
            JsonNode comments = getJson("priya.raman", "/api/incidents/" + incident + "/comments");
            assertThat(comments.findValuesAsText("body")).anyMatch(b -> b.startsWith("Else branch for INC-")).noneMatch(b -> b.startsWith("Yes branch"));
            mvc.perform(jsonBody(as("riya.varma", post("/api/workflows/" + wf + "/test")), Map.of("incidentId", incident)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(false)).andExpect(jsonPath("$.branch").value("NO"))
                    .andExpect(jsonPath("$.plannedActions[0]").value(org.hamcrest.Matchers.startsWith("Add work note: Else branch")));
            JsonNode history = getJson("priya.raman", "/api/workflows/executions?size=50");
            assertThat(history.get("content").findValuesAsText("workflowName")).contains("Else branch " + marker);
            assertThat(history.get("content").findValuesAsText("branch")).contains("NO");
        } finally {
            deactivate(wf);
        }
    }

    @Test
    void changeStatusAndNotifyUserActions() throws Exception {
        String marker = marker("wfstatus");
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), workflow("Bad status " + marker, "INCIDENT_CREATED",
                definition(List.of(titleContains(marker)), List.of(action("CHANGE_STATUS", Map.of("status", "BOGUS"))), List.of()), 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), workflow("Resolve without code " + marker, "INCIDENT_CREATED",
                definition(List.of(titleContains(marker)), List.of(action("CHANGE_STATUS", Map.of("status", "RESOLVED"))), List.of()), 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), workflow("Bad user " + marker, "INCIDENT_CREATED",
                definition(List.of(titleContains(marker)), List.of(action("NOTIFY_USER", Map.of("userEmail", "nobody@nowhere.io"))), List.of()), 0)))
                .andExpect(status().isBadRequest());
        long wf = create(workflow("Start work " + marker, "INCIDENT_CREATED", definition(List.of(titleContains(marker)),
                List.of(action("CHANGE_STATUS", Map.of("status", "IN_PROGRESS")), action("CHANGE_STATUS", Map.of("status", "REOPENED")),
                        action("NOTIFY_USER", Map.of("userEmail", email("sunita.joshi"), "message", "{{number}} picked up automatically"))),
                List.of()), 0));
        try {
            long incident = createIncident("divya.pillai", Map.of("title", "Bank statement import " + marker + " duplicated",
                    "description", "Rows twice", "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();
            await(() -> count("select count(*) from workflow_executions where workflow_id = ? and status = 'SUCCEEDED'", wf) == 1,
                    "status workflow to succeed");
            assertThat(getJson("priya.raman", "/api/incidents/" + incident).get("status").asText()).isEqualTo("IN_PROGRESS");
            String steps = jdbc.queryForObject("select steps::text from workflow_executions where workflow_id = ?", String.class, wf);
            assertThat(steps).contains("SKIPPED").contains("is not allowed");
            assertThat(count("select count(*) from notifications n join users u on u.id = n.user_id where u.email = ? and n.link = ?",
                    email("sunita.joshi"), "/incidents/" + incident)).isPositive();
        } finally {
            deactivate(wf);
        }
    }

    @Test
    void priorityChangedTriggerFiresWhenThePriorityChanges() throws Exception {
        String marker = marker("wfprio");
        long wf = create(workflow("Priority watch " + marker, "PRIORITY_CHANGED", definition(List.of(titleContains(marker)),
                List.of(action("ADD_COMMENT", Map.of("message", "Priority is now {{priority}}", "internal", "true"))), List.of()), 0));
        try {
            String title = "Payment run approval " + marker + " stuck";
            long incident = createIncident("divya.pillai", Map.of("title", title, "description", "Approval spinner",
                    "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();
            Thread.sleep(300);
            assertThat(count("select count(*) from workflow_executions where workflow_id = ?", wf)).isZero();
            mvc.perform(jsonBody(as("riya.varma", put("/api/incidents/" + incident)), Map.of("title", title, "description", "Approval spinner",
                    "category", "APPLICATION", "impact", "HIGH", "urgency", "HIGH", "affectedUsers", 4))).andExpect(status().isOk());
            await(() -> count("select count(*) from workflow_executions where workflow_id = ? and trigger_event = 'PRIORITY_CHANGED' "
                    + "and status = 'SUCCEEDED'", wf) == 1, "priority workflow");
            assertThat(getJson("riya.varma", "/api/incidents/" + incident + "/comments").findValuesAsText("body"))
                    .contains("Priority is now P1 CRITICAL");
        } finally {
            deactivate(wf);
        }
    }

    @Test
    void scheduledWorkflowsProcessEachIncidentOncePerInterval() throws Exception {
        String marker = marker("wfsched");
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), workflow("No schedule " + marker, "SCHEDULED",
                definition(List.of(titleContains(marker)), List.of(action("NOTIFY_ASSIGNEE", Map.of())), List.of()), 0)))
                .andExpect(status().isBadRequest());
        Map<String, Object> def = definition(List.of(titleContains(marker)),
                List.of(action("ADD_COMMENT", Map.of("message", "Scheduled check of {{number}}", "internal", "true"))), List.of());
        def.put("schedule", Map.of("intervalMinutes", 10));
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), workflow("Too frequent " + marker, "SCHEDULED", def, 0)))
                .andExpect(status().isBadRequest());
        def.put("schedule", Map.of("intervalMinutes", 15));
        long wf = create(workflow("Scheduled " + marker, "SCHEDULED", def, 0));
        try {
            long incident = createIncident("divya.pillai", Map.of("title", "Cash forecast sheet " + marker + " locked",
                    "description", "Sheet locked", "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();
            assertThat(engine.processScheduled(wf)).hasSize(1);
            assertThat(engine.processScheduled(wf)).isEmpty(); // already processed within this interval
            testClock().advance(Duration.ofMinutes(16));
            assertThat(engine.processScheduled(wf)).hasSize(1);
            assertThat(count("select count(*) from workflow_executions where workflow_id = ? and incident_id = ? and trigger_event = 'SCHEDULED' "
                    + "and status = 'SUCCEEDED'", wf, incident)).isEqualTo(2);
            engine.runDueScheduled();
            assertThat(jdbc.queryForObject("select last_scheduled_run_at is not null from workflows where id = ?", Boolean.class, wf)).isTrue();
            assertThat(count("select count(*) from workflow_executions where workflow_id = ?", wf)).isEqualTo(2);
            JsonNode saved = getJson("priya.raman", "/api/workflows/" + wf);
            assertThat(saved.at("/definition/schedule/intervalMinutes").asInt()).isEqualTo(15);
        } finally {
            deactivate(wf);
        }
    }

    @Test
    void templatesAreValidWorkflowsAndLayoutOnlyEditsKeepTheVersion() throws Exception {
        JsonNode templates = getJson("riya.varma", "/api/workflows/templates");
        assertThat(templates.findValuesAsText("key")).contains("payroll-auto-resolution", "critical-auto-triage", "sla-breach-escalation",
                "stale-pending-reminder");
        String marker = marker("tpl");
        for (JsonNode t : templates) {
            Map<String, Object> wf = workflow(t.get("name").asText() + " " + marker, t.get("triggerEvent").asText(),
                    json.convertValue(t.get("definition"), Map.class), 0);
            wf.put("active", false);
            create(wf);
            if (t.get("key").asText().equals("payroll-auto-resolution")) {
                assertThat(t.at("/definition/elseActions/0/type").asText()).isEqualTo("ASSIGN_DEPARTMENT");
                assertThat(t.at("/definition/actions").findValuesAsText("type")).containsExactly("START_RUNBOOK", "NOTIFY_DEPARTMENT");
                assertThat(t.at("/definition/actions/0/params/runbookId").asText()).isNotBlank();
            }
            if (t.get("key").asText().equals("stale-pending-reminder")) {
                assertThat(t.get("triggerEvent").asText()).isEqualTo("SCHEDULED");
                assertThat(t.at("/definition/schedule/intervalMinutes").asInt()).isEqualTo(240);
            }
        }

        Map<String, Object> def = definition(List.of(titleContains(marker)), List.of(action("NOTIFY_ASSIGNEE", Map.of())), List.of());
        Map<String, Object> wf = workflow("Layout " + marker, "INCIDENT_CREATED", def, 0);
        wf.put("active", false);
        long id = create(wf);
        def.put("layout", Map.of("trigger", Map.of("x", 40, "y", 60), "action-0", Map.of("x", 320.5, "y", 60)));
        mvc.perform(jsonBody(as("priya.raman", put("/api/workflows/" + id)), wf)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(1)).andExpect(jsonPath("$.definition.layout['action-0'].x").value(320.5));
        def.put("actions", List.of(action("NOTIFY_ROLE", Map.of("role", "INCIDENT_MANAGER"))));
        mvc.perform(jsonBody(as("priya.raman", put("/api/workflows/" + id)), wf)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2));
        JsonNode meta = getJson("riya.varma", "/api/workflows/meta");
        assertThat(meta.toString()).contains("PRIORITY_CHANGED", "SCHEDULED", "slaRemainingMinutes", "assignedTeam", "assigneeEmail",
                "CHANGE_STATUS", "NOTIFY_USER", "Create escalation", "Trigger approved runbook", "Send notification to role");
    }

    @Test
    void aFinalFailureNotifiesTheCreatorAndSystemAdministrators() throws Exception {
        String marker = marker("wfdead");
        long integrationId = body(mvc.perform(jsonBody(as("admin", post("/api/integrations")), Map.of("name", "Dead endpoint " + marker,
                "type", "WEBHOOK", "enabled", true, "endpointUrl", "http://127.0.0.1:9/unreachable", "subscribedEvents", List.of())))
                .andExpect(status().isCreated()).andReturn()).at("/integration/id").asLong();
        long wf = create(workflow("Failing " + marker, "INCIDENT_CREATED", definition(List.of(titleContains(marker)),
                List.of(action("SEND_WEBHOOK", Map.of("integrationId", String.valueOf(integrationId)))), List.of()), 0));
        try {
            createIncident("divya.pillai", Map.of("title", "Treasury feed " + marker + " silent", "description", "No feed"));
            await(() -> count("select count(*) from workflow_executions where workflow_id = ? and status = 'FAILED'", wf) == 1, "final failure");
            String title = "Workflow failed: Failing " + marker;
            await(() -> count("select count(*) from notifications n join users u on u.id = n.user_id where u.email = ? and n.type = 'WORKFLOW_FAILED' "
                    + "and n.title = ?", email("priya.raman"), title) == 1, "creator notified");
            assertThat(count("select count(*) from notifications n join users u on u.id = n.user_id where u.email = ? and n.type = 'WORKFLOW_FAILED' "
                    + "and n.title = ?", email("admin"), title)).isEqualTo(1);
            assertThat(count("select count(*) from notifications n join users u on u.id = n.user_id where u.email = ? and n.type = 'WORKFLOW_FAILED' "
                    + "and n.title = ?", email("kiran.rao"), title)).isZero(); // other IT managers are not notified
        } finally {
            deactivate(wf);
        }
    }
}

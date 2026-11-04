package com.nexus360.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Change management: server-side state machine, CAB through the approval engine (HIGH risk adds the CAB stage), standard
 * changes from templates, emergency changes, scheduling conflicts and freeze windows, the calendar, maintenance status,
 * incident correlation, metrics, AI risk fallback and the CHANGE_REQUEST_CREATED trigger / REQUEST_APPROVAL action.
 */
class ChangeIT extends IntegrationTest {

    static final Map<String, String> SAFE = Map.of("userImpact", "NONE", "tested", "YES", "rollbackTested", "YES", "downtime", "NONE",
            "complexity", "LOW", "experience", "YES");
    static final Map<String, String> RISKY = Map.of("userImpact", "ALL", "tested", "NO", "rollbackTested", "NOT_POSSIBLE", "downtime", "HOURS",
            "complexity", "HIGH", "experience", "NO");

    @Autowired
    JdbcTemplate jdbc;

    private long service(String code) {
        return jdbc.queryForObject("select id from business_services where code = ?", Long.class, code);
    }

    private long userId(String user) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email(user));
    }

    /** A random weekend-night window far in the future, so tests do not collide with each other or the demo data. */
    private static Instant futureWindow() {
        return Instant.now().plus(Duration.ofDays(120 + ThreadLocalRandom.current().nextInt(600))).truncatedTo(ChronoUnit.HOURS);
    }

    private JsonNode create(String user, Map<String, Object> body) throws Exception {
        return body(mvc.perform(jsonBody(as(user, post("/api/changes")), body)).andExpect(status().isCreated()).andReturn());
    }

    private Map<String, Object> normal(String service, Map<String, String> answers, Instant start) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", unique("Change on " + service));
        m.put("type", "NORMAL");
        m.put("serviceIds", List.of(service(service)));
        m.put("implementationPlan", "Apply the change in three steps");
        m.put("rollbackPlan", "Restore the previous version");
        m.put("riskAnswers", answers);
        m.put("assigneeId", userId("dev.nair"));
        if (start != null) {
            m.put("plannedStart", start.toString());
            m.put("plannedEnd", start.plus(Duration.ofHours(2)).toString());
        }
        return m;
    }

    private org.springframework.test.web.servlet.ResultActions move(String user, long id, Map<String, Object> body) throws Exception {
        return mvc.perform(jsonBody(as(user, post("/api/changes/" + id + "/transition")), body));
    }

    private JsonNode toCab(long id) throws Exception {
        move("anil.kumar", id, Map.of("status", "SUBMITTED")).andExpect(status().isOk());
        move("anil.kumar", id, Map.of("status", "ASSESSMENT")).andExpect(status().isOk());
        return body(move("anil.kumar", id, Map.of("status", "AWAITING_APPROVAL")).andExpect(status().isOk()).andReturn());
    }

    /** Low-risk change: one change-authority decision approves it. */
    private long approvedLowRisk(String service, Instant start) throws Exception {
        long id = create("anil.kumar", normal(service, SAFE, start)).get("id").asLong();
        long approval = toCab(id).at("/approval/id").asLong();
        mvc.perform(jsonBody(as("kiran.rao", post("/api/approvals/" + approval + "/decision")), Map.of("approved", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        return id;
    }

    @Test
    void highRiskNormalChangeNeedsTheCabStageAndFollowsTheLifecycle() throws Exception {
        Instant start = futureWindow();
        JsonNode c = create("anil.kumar", normal("HR-PORTAL", RISKY, start));
        long id = c.get("id").asLong();
        assertThat(c.get("number").asText()).startsWith("CHG-");
        assertThat(c.get("risk").asText()).isEqualTo("HIGH");
        assertThat(c.get("riskFactors").findValuesAsText("key")).contains("userImpact", "tier");
        move("anil.kumar", id, Map.of("status", "SCHEDULED")).andExpect(status().isConflict()); // DRAFT cannot be scheduled
        move("anil.kumar", id, Map.of("status", "SUBMITTED")).andExpect(status().isOk());
        move("anil.kumar", id, Map.of("status", "ASSESSMENT")).andExpect(status().isOk());
        JsonNode waiting = body(move("anil.kumar", id, Map.of("status", "AWAITING_APPROVAL")).andExpect(status().isOk()).andReturn());
        assertThat(waiting.get("status").asText()).isEqualTo("AWAITING_APPROVAL");
        assertThat(waiting.at("/approval/flowName").asText()).isEqualTo("Normal change (CAB)");
        assertThat(waiting.at("/approval/stageCount").asInt()).isEqualTo(2);
        assertThat(waiting.get("allowedTransitions").toString()).isEqualTo("[\"CANCELLED\"]");
        move("anil.kumar", id, Map.of("status", "APPROVED")).andExpect(status().isConflict()); // only the engine approves
        long approval = waiting.at("/approval/id").asLong();

        mvc.perform(jsonBody(as("anil.kumar", post("/api/approvals/" + approval + "/decision")), Map.of("approved", true)))
                .andExpect(status().isForbidden()); // the requester
        mvc.perform(jsonBody(as("priya.raman", post("/api/approvals/" + approval + "/decision")), Map.of("approved", true, "comment", "Authority OK")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.currentStageName").value("Change Advisory Board"));
        mvc.perform(jsonBody(as("priya.raman", post("/api/approvals/" + approval + "/decision")), Map.of("approved", true)))
                .andExpect(status().isOk()); // CAB vote 1 (a different stage)
        mvc.perform(jsonBody(as("kiran.rao", post("/api/approvals/" + approval + "/decision")), Map.of("approved", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        JsonNode approved = getJson("anil.kumar", "/api/changes/" + id);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("approvedAt").isNull()).isFalse();

        move("anil.kumar", id, Map.of("status", "SCHEDULED")).andExpect(status().isOk());
        move("riya.varma", id, Map.of("status", "IMPLEMENTING")).andExpect(status().isForbidden()); // not the implementer
        move("dev.nair", id, Map.of("status", "IMPLEMENTING")).andExpect(status().isOk()).andExpect(jsonPath("$.actualStart").isNotEmpty());
        move("dev.nair", id, Map.of("status", "FAILED")).andExpect(status().isBadRequest()); // outcome notes needed
        move("dev.nair", id, Map.of("status", "FAILED", "outcomeNotes", "Migration script failed")).andExpect(status().isOk());
        move("dev.nair", id, Map.of("status", "ROLLED_BACK", "outcomeNotes", "Restored")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mvc.perform(jsonBody(as("anil.kumar", post("/api/changes/" + id + "/review")), Map.of("notes", "Missing rehearsal of the migration")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pirCompletedAt").isNotEmpty());
        assertThat(getJson("anil.kumar", "/api/changes/" + id + "/history").findValuesAsText("toValue"))
                .containsSubsequence("SUBMITTED", "ASSESSMENT", "AWAITING_APPROVAL", "APPROVED", "SCHEDULED", "IMPLEMENTING", "FAILED", "ROLLED_BACK");
        JsonNode metrics = getJson("ananya.bose", "/api/changes/metrics?days=30");
        assertThat(metrics.get("rolledBack").asLong()).isPositive();
        assertThat(metrics.get("successRate").isNull()).isFalse();
    }

    @Test
    void lowRiskSkipsTheCabStageAndRejectionCanBeReworked() throws Exception {
        long id = approvedLowRisk("FACILITIES", futureWindow());
        JsonNode c = getJson("anil.kumar", "/api/changes/" + id);
        assertThat(c.get("risk").asText()).isEqualTo("LOW");
        JsonNode approval = getJson("admin", "/api/approvals/" + c.at("/approval/id").asLong());
        assertThat(approval.at("/stages/1/status").asText()).isEqualTo("SKIPPED");

        long rejected = create("anil.kumar", normal("FACILITIES", SAFE, futureWindow())).get("id").asLong();
        long a = toCab(rejected).at("/approval/id").asLong();
        mvc.perform(jsonBody(as("priya.raman", post("/api/approvals/" + a + "/decision")), Map.of("approved", false, "comment", "Window too short")))
                .andExpect(status().isOk());
        JsonNode r = getJson("anil.kumar", "/api/changes/" + rejected);
        assertThat(r.get("status").asText()).isEqualTo("REJECTED");
        assertThat(r.get("outcomeNotes").asText()).contains("Window too short");
        move("anil.kumar", rejected, Map.of("status", "DRAFT")).andExpect(status().isOk());
    }

    @Test
    void standardAndEmergencyChangesAndWhoMayRaiseThem() throws Exception {
        mvc.perform(jsonBody(as("riya.varma", post("/api/changes")), normal("EUC", SAFE, null))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/changes")), normal("EUC", SAFE, null))).andExpect(status().isForbidden());
        JsonNode templates = getJson("riya.varma", "/api/changes/templates");
        assertThat(templates.findValuesAsText("code")).contains("STD-WIN-PATCH", "STD-CERT-RENEW", "STD-DB-INDEX");
        long template = templates.findParents("code").stream().filter(t -> t.get("code").asText().equals("STD-WIN-PATCH")).findFirst()
                .orElseThrow().get("id").asLong();
        mvc.perform(jsonBody(as("anil.kumar", post("/api/changes")), Map.of("title", "No template", "type", "STANDARD"))).andExpect(status().isBadRequest());
        Instant start = futureWindow();
        JsonNode std = create("anil.kumar", Map.of("type", "STANDARD", "templateId", template, "plannedStart", start.toString()));
        assertThat(std.get("risk").asText()).isEqualTo("LOW");
        assertThat(std.get("plannedEnd").asText()).isEqualTo(start.plus(Duration.ofMinutes(240)).toString());
        JsonNode submitted = body(move("anil.kumar", std.get("id").asLong(), Map.of("status", "SUBMITTED")).andExpect(status().isOk()).andReturn());
        assertThat(submitted.get("status").asText()).isEqualTo("APPROVED"); // pre-approved, no CAB
        assertThat(submitted.get("approval").isNull()).isTrue();

        Map<String, Object> e = normal("EUC", RISKY, null);
        e.put("type", "EMERGENCY");
        JsonNode emergency = create("sunita.joshi", e);
        assertThat(emergency.get("pirRequired").asBoolean()).isTrue();
        assertThat(emergency.get("riskFactors").findValuesAsText("key")).contains("emergency");
        JsonNode ecab = body(move("sunita.joshi", emergency.get("id").asLong(), Map.of("status", "SUBMITTED")).andExpect(status().isOk()).andReturn());
        assertThat(ecab.get("status").asText()).isEqualTo("AWAITING_APPROVAL");
        assertThat(ecab.at("/approval/flowName").asText()).isEqualTo("Emergency change (ECAB)");
        assertThat(ecab.at("/approval/dueAt").isNull()).isFalse(); // expedited: 2 h deadline
        mvc.perform(jsonBody(as("anil.kumar", post("/api/approvals/" + ecab.at("/approval/id").asLong() + "/decision")), Map.of("approved", true)))
                .andExpect(status().isOk());
        assertThat(getJson("anil.kumar", "/api/my-work?limit=50").at("/changes/items").findValuesAsText("number")).contains(std.get("number").asText());
        move("riya.varma", emergency.get("id").asLong(), Map.of("status", "CANCELLED", "reason", "x")).andExpect(status().isForbidden());
        move("sunita.joshi", emergency.get("id").asLong(), Map.of("status", "CANCELLED")).andExpect(status().isBadRequest());
        move("sunita.joshi", emergency.get("id").asLong(), Map.of("status", "CANCELLED", "reason", "Outage resolved without it")).andExpect(status().isOk());
    }

    @Test
    void schedulingDetectsConflictsAndRespectsFreezeWindows() throws Exception {
        Instant start = futureWindow();
        long a = approvedLowRisk("EUC", start);
        move("anil.kumar", a, Map.of("status", "SCHEDULED")).andExpect(status().isOk());
        long b = approvedLowRisk("EUC", start.plus(Duration.ofMinutes(30)));
        JsonNode bDetail = getJson("anil.kumar", "/api/changes/" + b);
        assertThat(bDetail.get("conflicts").findValuesAsText("changeId")).contains(String.valueOf(a));
        move("anil.kumar", b, Map.of("status", "SCHEDULED")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Scheduling conflict")));
        move("anil.kumar", b, Map.of("status", "SCHEDULED", "overrideConflicts", true)).andExpect(status().isBadRequest()); // reason
        move("anil.kumar", b, Map.of("status", "SCHEDULED", "overrideConflicts", true, "overrideReason", "Different devices, same tool"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conflictOverrideReason").value("Different devices, same tool"));
        JsonNode calendar = getJson("riya.varma", "/api/changes/calendar?from=" + start.minus(Duration.ofDays(1)) + "&to=" + start.plus(Duration.ofDays(1))
                + "&serviceId=" + service("EUC"));
        assertThat(calendar.get("entries").findValuesAsText("id")).contains(String.valueOf(a), String.valueOf(b));
        assertThat(calendar.get("conflicts").findValuesAsText("changeA")).isNotEmpty();
        mvc.perform(as("riya.varma", get("/api/changes/calendar?from=" + start + "&to=" + start.plus(Duration.ofDays(200))))).andExpect(status().isBadRequest());

        // Freeze windows: NORMAL changes may not be scheduled into them, EMERGENCY changes may.
        Instant freezeStart = futureWindow().plus(Duration.ofDays(800));
        mvc.perform(jsonBody(as("riya.varma", post("/api/changes/freeze-windows")), Map.of("name", "x", "startsAt", freezeStart.toString(),
                "endsAt", freezeStart.plus(Duration.ofDays(2)).toString()))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("anil.kumar", post("/api/changes/freeze-windows")), Map.of("name", unique("Year-end freeze"), "startsAt",
                freezeStart.toString(), "endsAt", freezeStart.plus(Duration.ofDays(2)).toString(), "serviceIds", List.of(service("FACILITIES")))))
                .andExpect(status().isCreated());
        long frozen = approvedLowRisk("FACILITIES", freezeStart.plus(Duration.ofHours(5)));
        assertThat(getJson("anil.kumar", "/api/changes/" + frozen).get("freezeWindows")).isNotEmpty();
        move("anil.kumar", frozen, Map.of("status", "SCHEDULED")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("change freeze")));
        long otherService = approvedLowRisk("HR-PORTAL", freezeStart.plus(Duration.ofHours(5)));
        move("anil.kumar", otherService, Map.of("status", "SCHEDULED")).andExpect(status().isOk()); // freeze is service-specific

        Map<String, Object> e = normal("FACILITIES", SAFE, freezeStart.plus(Duration.ofHours(10)));
        e.put("type", "EMERGENCY");
        long emergency = create("anil.kumar", e).get("id").asLong();
        long approval = body(move("anil.kumar", emergency, Map.of("status", "SUBMITTED")).andReturn()).at("/approval/id").asLong();
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/approvals/" + approval + "/decision")), Map.of("approved", true))).andExpect(status().isOk());
        move("anil.kumar", emergency, Map.of("status", "SCHEDULED")).andExpect(status().isOk());
        assertThat(getJson("anil.kumar", "/api/changes/" + emergency + "/history").findValuesAsText("note"))
                .anyMatch(n -> n != null && n.startsWith("Inside change freeze"));
    }

    @Test
    void changesInTheirWindowPutServicesIntoMaintenanceAndCorrelateIncidents() throws Exception {
        Instant now = clock.instant();
        long id = create("anil.kumar", normal("FACILITIES", SAFE, now.minus(Duration.ofMinutes(10)))).get("id").asLong();
        long approval = toCab(id).at("/approval/id").asLong();
        mvc.perform(jsonBody(as("kiran.rao", post("/api/approvals/" + approval + "/decision")), Map.of("approved", true))).andExpect(status().isOk());
        move("anil.kumar", id, Map.of("status", "SCHEDULED")).andExpect(status().isOk());
        String number = getJson("anil.kumar", "/api/changes/" + id).get("number").asText();
        JsonNode svc = getJson("kavya.reddy", "/api/services/" + service("FACILITIES"));
        assertThat(svc.get("status").asText()).isEqualTo("MAINTENANCE");
        assertThat(svc.get("statusReason").asText()).contains(number);
        move("dev.nair", id, Map.of("status", "IMPLEMENTING")).andExpect(status().isOk());
        long incident = createIncident("kavya.reddy", Map.of("title", unique("Meeting room booking screens blank"), "description",
                "All booking screens went blank", "serviceId", service("FACILITIES"))).at("/incident/id").asLong();
        JsonNode corr = getJson("anil.kumar", "/api/changes/" + id + "/incidents?hours=4");
        assertThat(corr.get("correlated").findValuesAsText("id")).contains(String.valueOf(incident));
        mvc.perform(jsonBody(as("dev.nair", post("/api/changes/" + id + "/incidents")), Map.of("incidentId", incident, "relation", "CAUSED_BY")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.linkedIncidents[0].relation").value("CAUSED_BY"));
        move("dev.nair", id, Map.of("status", "COMPLETED")).andExpect(status().isOk());
        assertThat(getJson("kavya.reddy", "/api/services/" + service("FACILITIES")).get("status").asText()).isNotEqualTo("MAINTENANCE");

        JsonNode ai = body(mvc.perform(as("anil.kumar", post("/api/ai/changes/" + id + "/risk"))).andExpect(status().isOk()).andReturn());
        assertThat(ai.get("source").asText()).isEqualTo("FALLBACK");
        assertThat(ai.get("notice").asText()).contains("No LLM was used");
        assertThat(ai.at("/data/score").asInt()).isEqualTo(getJson("anil.kumar", "/api/changes/" + id).get("riskScore").asInt());
        assertThat(ai.at("/data/recommendations")).isNotEmpty();
        assertThat(getJson("kavya.reddy", "/api/portal/overview").has("plannedChanges")).isTrue();
        mvc.perform(as("kavya.reddy", get("/api/changes"))).andExpect(status().isForbidden());
    }

    @Test
    void changeRequestCreatedTriggersWorkflowsAndRequestApprovalWorks() throws Exception {
        String token = unique("wf").replace(" ", "");
        JsonNode wf = body(mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), Map.of("name", "Risky change alert " + token,
                "triggerEvent", "CHANGE_REQUEST_CREATED", "active", true, "maxExecutionsPerHour", 100, "maxRetries", 0, "retryBackoffSeconds", 30,
                "definition", Map.of("conditions", Map.of("match", "ALL", "rules", List.of(Map.of("field", "changeRisk", "operator", "EQUALS", "value", "HIGH"))),
                        "actions", List.of(Map.of("type", "NOTIFY_USER", "params", Map.of("userEmail", email("riya.varma"), "message", "{{number}} is {{risk}}")))))))
                .andExpect(status().isCreated()).andReturn());
        long change = create("anil.kumar", normal("HR-PORTAL", RISKY, null)).get("id").asLong();
        await(() -> {
            try {
                JsonNode ex = getJson("priya.raman", "/api/workflows/executions?workflowId=" + wf.get("id").asLong());
                return ex.get("content").size() > 0 && ex.at("/content/0/changeId").asLong() == change
                        && "SUCCEEDED".equals(ex.at("/content/0/status").asText());
            } catch (Exception e) {
                return false;
            }
        }, "CHANGE_REQUEST_CREATED workflow execution");

        // REQUEST_APPROVAL on an incident: a WORKFLOW sign-off whose decision becomes a work note.
        JsonNode signOff = body(mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), Map.of("name", "Sign-off " + token,
                "triggerEvent", "INCIDENT_CREATED", "active", true, "maxExecutionsPerHour", 100, "maxRetries", 0, "retryBackoffSeconds", 30,
                "definition", Map.of("conditions", Map.of("match", "ALL", "rules", List.of(Map.of("field", "title", "operator", "CONTAINS", "value", token))),
                        "actions", List.of(Map.of("type", "REQUEST_APPROVAL", "params", Map.of("flowCode", "CHANGE_EMERGENCY")))))))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), Map.of("name", "Bad " + token, "triggerEvent", "INCIDENT_CREATED",
                "active", true, "maxExecutionsPerHour", 100, "maxRetries", 0, "retryBackoffSeconds", 30, "definition", Map.of("conditions",
                        Map.of("match", "ALL", "rules", List.of()), "actions", List.of(Map.of("type", "REQUEST_APPROVAL", "params", Map.of("flowCode", "NOPE")))))))
                .andExpect(status().isBadRequest());
        long incident = createIncident("kavya.reddy", Map.of("title", "Needs sign-off " + token, "description", "Workflow sign-off test"))
                .at("/incident/id").asLong();
        String number = getJson("kavya.reddy", "/api/incidents/" + incident).get("number").asText();
        long[] approvalId = new long[1];
        await(() -> {
            try {
                for (JsonNode a : getJson("sunita.joshi", "/api/approvals?type=WORKFLOW&status=PENDING&size=100").get("content")) {
                    if (number.equals(a.get("referenceNumber").asText())) {
                        approvalId[0] = a.get("id").asLong();
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        }, "WORKFLOW approval for " + number);
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/approvals/" + approvalId[0] + "/decision")), Map.of("approved", true, "comment", "Fine")))
                .andExpect(status().isOk());
        assertThat(getJson("sunita.joshi", "/api/incidents/" + incident + "/comments").findValuesAsText("body"))
                .anyMatch(b -> b.startsWith("Approved: Sign-off for " + number));
        assertThat(signOff.get("id").asLong()).isPositive();
    }

    @Test
    void demoChangesCoverTheLifecycle() throws Exception {
        JsonNode list = getJson("anil.kumar", "/api/changes?size=100&sort=createdAt,asc");
        List<String> statuses = list.get("content").findValuesAsText("status");
        assertThat(statuses).contains("AWAITING_APPROVAL", "APPROVED", "SCHEDULED", "COMPLETED", "ROLLED_BACK", "REJECTED", "DRAFT");
        assertThat(list.get("content").findValuesAsText("type")).contains("STANDARD", "NORMAL", "EMERGENCY");
        assertThat(getJson("anil.kumar", "/api/changes?type=EMERGENCY&status=COMPLETED").at("/content/0/pirDone").asBoolean()).isFalse();
        assertThat(getJson("anil.kumar", "/api/changes/freeze-windows").findValuesAsText("name")).contains("Month-end payroll close");
        assertThat(getJson("anil.kumar", "/api/changes/risk-questions").findValuesAsText("key")).contains("userImpact", "rollbackTested");
    }
}

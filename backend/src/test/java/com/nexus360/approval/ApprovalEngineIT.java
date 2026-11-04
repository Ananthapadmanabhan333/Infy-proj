package com.nexus360.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.approval.ApprovalModel.ApproverDef;
import com.nexus360.approval.ApprovalModel.ApproverKind;
import com.nexus360.approval.ApprovalModel.EscalationDef;
import com.nexus360.approval.ApprovalModel.Quorum;
import com.nexus360.approval.ApprovalModel.StageDef;
import com.nexus360.approval.ApprovalModel.StageMode;
import com.nexus360.security.Actor;
import com.nexus360.support.IntegrationTest;
import com.nexus360.user.Role;
import com.nexus360.user.UserRepository;
import com.nexus360.workflow.WorkflowModel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Approval engine v2: stage modes, approver kinds, quorum ANY/ALL/N, conditional stages, segregation of duties, one decision
 * per person and stage, rejection reasons, delegation (per task and out-of-office), deadline escalation (idempotent,
 * auto-reject), history and the cross-subject task inbox.
 */
class ApprovalEngineIT extends IntegrationTest {

    @Autowired
    ApprovalService approvals;

    @Autowired
    ApprovalDeadlineScheduler deadlines;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private Actor actor(String user) {
        return Actor.of(users.findByEmailIgnoreCase(email(user)).orElseThrow());
    }

    private long userId(String user) {
        return users.findByEmailIgnoreCase(email(user)).orElseThrow().getId();
    }

    private long teamId(String code) {
        return jdbc.queryForObject("select id from teams where code = ?", Long.class, code);
    }

    private static ApproverDef role(Role r) {
        return new ApproverDef(ApproverKind.ROLE, null, r, null);
    }

    private Approval request(String requester, Map<String, String> facts, StageDef... stages) {
        return approvals.requestFlow(new ApprovalService.FlowRequest(Approval.Type.PIR, 1L, "PIR-" + unique("T").replace(" ", ""),
                unique("Review sign-off"), "Test approval", Map.of(), null, List.of(stages), facts, actor(requester), false, "/approvals"));
    }

    private JsonNode detail(String user, long id) throws Exception {
        return getJson(user, "/api/approvals/" + id);
    }

    private long pendingTask(String user, long approvalId) throws Exception {
        for (JsonNode t : getJson(user, "/api/approvals/tasks?size=100").get("content")) {
            if (t.at("/approval/id").asLong() == approvalId) {
                return t.get("taskId").asLong();
            }
        }
        throw new AssertionError("No open task of approval " + approvalId + " for " + user);
    }

    private boolean hasTask(String user, long approvalId) throws Exception {
        for (JsonNode t : getJson(user, "/api/approvals/tasks?size=100").get("content")) {
            if (t.at("/approval/id").asLong() == approvalId) {
                return true;
            }
        }
        return false;
    }

    private org.springframework.test.web.servlet.ResultActions decide(String user, long taskId, boolean approved, String comment) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("approved", approved);
        if (comment != null) {
            body.put("comment", comment);
        }
        return mvc.perform(jsonBody(as(user, post("/api/approvals/tasks/" + taskId + "/decision")), body));
    }

    @Test
    void sequentialStagesWithQuorumNeedDifferentPeopleAndKeepHistory() throws Exception {
        Approval a = request("dev.nair", Map.of(),
                new StageDef("Two IT managers", StageMode.PARALLEL, Quorum.N, 2, List.of(role(Role.IT_MANAGER), role(Role.IT_MANAGER),
                        role(Role.INCIDENT_MANAGER)), null, null, null),
                new StageDef("Incident manager", StageMode.SEQUENTIAL, Quorum.ALL, null, List.of(role(Role.INCIDENT_MANAGER)), null, null, null));
        long id = a.getId();
        JsonNode d = detail("priya.raman", id);
        assertThat(d.at("/approval/stageCount").asInt()).isEqualTo(2);
        assertThat(d.at("/stages/0/status").asText()).isEqualTo("ACTIVE");
        assertThat(d.at("/stages/1/status").asText()).isEqualTo("WAITING");
        assertThat(d.at("/approval/canDecide").asBoolean()).isTrue();

        long first = pendingTask("priya.raman", id);
        decide("priya.raman", first, true, "Looks fine").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        // One decision per person and stage.
        long second = detail("priya.raman", id).at("/stages/0/tasks/1/id").asLong();
        decide("priya.raman", second, true, "again").andExpect(status().isConflict());
        mvc.perform(jsonBody(as("priya.raman", post("/api/approvals/" + id + "/decision")), Map.of("approved", true)))
                .andExpect(status().isConflict());
        decide("kiran.rao", pendingTask("kiran.rao", id), true, null).andExpect(status().isOk());

        d = detail("sunita.joshi", id);
        assertThat(d.at("/stages/0/status").asText()).isEqualTo("APPROVED");
        assertThat(d.at("/stages/0/tasks/2/status").asText()).isEqualTo("SKIPPED"); // quorum reached
        assertThat(d.at("/stages/1/status").asText()).isEqualTo("ACTIVE");
        assertThat(d.at("/approval/currentStage").asInt()).isEqualTo(2);
        decide("sunita.joshi", pendingTask("sunita.joshi", id), true, "OK").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        List<String> events = new ArrayList<>();
        detail("sunita.joshi", id).get("history").forEach(e -> events.add(e.get("type").asText()));
        assertThat(events).containsSubsequence("REQUESTED", "STAGE_STARTED", "TASK_APPROVED", "TASK_APPROVED", "STAGE_APPROVED", "STAGE_STARTED",
                "TASK_APPROVED", "STAGE_APPROVED", "APPROVED");
        // The requester got the outcome; nobody else can see the approval.
        assertThat(getJson("dev.nair", "/api/notifications?size=30").at("/content").findValuesAsText("type")).contains("APPROVAL_DECIDED");
        mvc.perform(as("vishal.kumar", get("/api/approvals/" + id))).andExpect(status().isNotFound());
        mvc.perform(as("ananya.bose", get("/api/approvals/" + id))).andExpect(status().isOk()); // auditors read everything
    }

    @Test
    void quorumAllRejectsAsSoonAsItCannotBeReachedAndNeedsAReason() throws Exception {
        Approval a = request("dev.nair", Map.of(), new StageDef("Everyone", StageMode.PARALLEL, Quorum.ALL, null,
                List.of(role(Role.IT_MANAGER), role(Role.INCIDENT_MANAGER)), null, null, null));
        long task = pendingTask("sunita.joshi", a.getId());
        decide("sunita.joshi", task, false, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please give a reason when rejecting"));
        decide("sunita.joshi", task, false, "No rollback plan").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionComment").value("No rollback plan"));
        JsonNode d = detail("priya.raman", a.getId());
        assertThat(d.at("/stages/0/status").asText()).isEqualTo("REJECTED");
        assertThat(d.at("/stages/0/tasks").findValuesAsText("status")).containsExactlyInAnyOrder("SKIPPED", "REJECTED");
    }

    @Test
    void sequentialStageOpensOneTaskAtATime() throws Exception {
        Approval a = request("dev.nair", Map.of(), new StageDef("In order", StageMode.SEQUENTIAL, Quorum.ALL, null,
                List.of(role(Role.INCIDENT_MANAGER), role(Role.IT_MANAGER)), null, null, null));
        assertThat(hasTask("priya.raman", a.getId())).isFalse(); // the IT manager task waits
        decide("sunita.joshi", pendingTask("sunita.joshi", a.getId()), true, null).andExpect(status().isOk());
        decide("priya.raman", pendingTask("priya.raman", a.getId()), true, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void conditionalStagesFollowTheSubjectFacts() throws Exception {
        WorkflowModel.Conditions highRisk = new WorkflowModel.Conditions("ALL",
                List.of(new WorkflowModel.ConditionRule("changeRisk", WorkflowModel.Operator.EQUALS, "HIGH")));
        StageDef first = new StageDef("Authority", StageMode.PARALLEL, Quorum.ANY, null, List.of(role(Role.INCIDENT_MANAGER)), null, null, null);
        StageDef cab = new StageDef("CAB", StageMode.PARALLEL, Quorum.ANY, null, List.of(role(Role.IT_MANAGER)), highRisk, null, null);
        Approval low = request("dev.nair", Map.of("changeRisk", "LOW"), first, cab);
        decide("sunita.joshi", pendingTask("sunita.joshi", low.getId()), true, null).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(detail("sunita.joshi", low.getId()).at("/stages/1/status").asText()).isEqualTo("SKIPPED");

        Approval high = request("dev.nair", Map.of("changeRisk", "HIGH"), first, cab);
        decide("sunita.joshi", pendingTask("sunita.joshi", high.getId()), true, null).andExpect(jsonPath("$.status").value("PENDING"));
        decide("priya.raman", pendingTask("priya.raman", high.getId()), true, null).andExpect(jsonPath("$.status").value("APPROVED"));

        // A flow whose only stage does not apply completes immediately.
        Approval none = request("dev.nair", Map.of("changeRisk", "LOW"), cab);
        assertThat(none.getStatus()).isEqualTo(Approval.Status.APPROVED);
    }

    @Test
    void segregationOfDutiesAppliesToEveryApproverKind() throws Exception {
        Approval a = request("priya.raman", Map.of(), new StageDef("Managers", StageMode.PARALLEL, Quorum.ANY, null,
                List.of(role(Role.IT_MANAGER)), null, null, null));
        long task = detail("priya.raman", a.getId()).at("/stages/0/tasks/0/id").asLong();
        assertThat(detail("priya.raman", a.getId()).at("/approval/canDecide").asBoolean()).isFalse();
        decide("priya.raman", task, true, "self").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ApprovalService.SOD_MESSAGE));
        assertThat(hasTask("priya.raman", a.getId())).isFalse(); // own approvals never appear as tasks
        decide("kiran.rao", pendingTask("kiran.rao", a.getId()), true, null).andExpect(status().isOk());
    }

    @Test
    void teamAndUserApproversDecideOnlyTheirTasks() throws Exception {
        Approval a = request("sunita.joshi", Map.of(),
                new StageDef("Team", StageMode.PARALLEL, Quorum.ANY, null, List.of(new ApproverDef(ApproverKind.TEAM, null, null, teamId("NET-OPS"))),
                        null, null, null),
                new StageDef("User", StageMode.PARALLEL, Quorum.ANY, null, List.of(new ApproverDef(ApproverKind.USER, userId("farhan.ali"), null, null)),
                        null, null, null));
        long teamTask = detail("sunita.joshi", a.getId()).at("/stages/0/tasks/0/id").asLong();
        decide("riya.varma", teamTask, true, null).andExpect(status().isForbidden()); // not a NET-OPS member
        decide("vikram.singh", pendingTask("vikram.singh", a.getId()), true, "Network ready").andExpect(status().isOk());
        long userTask = pendingTask("farhan.ali", a.getId());
        decide("arjun.kapoor", userTask, true, null).andExpect(status().isForbidden());
        decide("farhan.ali", userTask, true, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        // The decided task stays visible in the decider's history.
        assertThat(getJson("farhan.ali", "/api/approvals/tasks?status=DECIDED&size=100").get("content").findValuesAsText("taskStatus")).contains("APPROVED");
    }

    @Test
    void tasksCanBeDelegatedAndOutOfOfficeDelegationsReroute() throws Exception {
        Approval a = request("dev.nair", Map.of(), new StageDef("User", StageMode.PARALLEL, Quorum.ANY, null,
                List.of(new ApproverDef(ApproverKind.USER, userId("thomas.mathew"), null, null)), null, null, null));
        long task = pendingTask("thomas.mathew", a.getId());
        mvc.perform(jsonBody(as("thomas.mathew", post("/api/approvals/tasks/" + task + "/delegate")), Map.of("userId", userId("dev.nair"))))
                .andExpect(status().isBadRequest()); // the requester
        mvc.perform(jsonBody(as("lakshmi.menon", post("/api/approvals/tasks/" + task + "/delegate")), Map.of("userId", userId("kiran.rao"))))
                .andExpect(status().isForbidden()); // not the approver
        mvc.perform(jsonBody(as("thomas.mathew", post("/api/approvals/tasks/" + task + "/delegate")), Map.of("userId", userId("kiran.rao"),
                "comment", "On leave"))).andExpect(status().isOk());
        decide("thomas.mathew", task, true, null).andExpect(status().isForbidden());
        decide("kiran.rao", task, true, "Covering").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        JsonNode d = detail("kiran.rao", a.getId());
        assertThat(d.at("/stages/0/tasks/0/delegatedFromName").asText()).isEqualTo("Thomas Mathew");
        assertThat(d.get("history").findValuesAsText("type")).contains("DELEGATED");

        // Out of office: tasks addressed to the delegator personally go to the delegate while the window is active.
        Instant now = clock.instant();
        JsonNode delegation = body(mvc.perform(jsonBody(as("lakshmi.menon", post("/api/approvals/delegations")), Map.of("delegateId",
                userId("farhan.ali"), "startsAt", now.minus(Duration.ofMinutes(1)).toString(), "endsAt", now.plus(Duration.ofDays(1)).toString(),
                "reason", "Conference"))).andExpect(status().isCreated()).andReturn());
        mvc.perform(jsonBody(as("lakshmi.menon", post("/api/approvals/delegations")), Map.of("delegateId", userId("farhan.ali"),
                "startsAt", now.toString(), "endsAt", now.plus(Duration.ofHours(2)).toString()))).andExpect(status().isConflict());
        Approval b = request("dev.nair", Map.of(), new StageDef("User", StageMode.PARALLEL, Quorum.ANY, null,
                List.of(new ApproverDef(ApproverKind.USER, userId("lakshmi.menon"), null, null)), null, null, null));
        decide("farhan.ali", pendingTask("farhan.ali", b.getId()), true, null).andExpect(status().isOk());
        mvc.perform(as("lakshmi.menon", delete("/api/approvals/delegations/" + delegation.get("id").asLong()))).andExpect(status().isNoContent());
        assertThat(getJson("lakshmi.menon", "/api/approvals/delegations").findValuesAsText("id")).doesNotContain(delegation.get("id").asText());
    }

    @Test
    void overdueStagesEscalateOnceAndMayAutoReject() throws Exception {
        Approval notify = request("dev.nair", Map.of(), new StageDef("Quick", StageMode.PARALLEL, Quorum.ANY, null,
                List.of(new ApproverDef(ApproverKind.USER, userId("farhan.ali"), null, null)), null, 2, new EscalationDef(true, Role.INCIDENT_MANAGER, false)));
        Approval reject = request("dev.nair", Map.of(), new StageDef("Strict", StageMode.PARALLEL, Quorum.ANY, null,
                List.of(role(Role.IT_MANAGER)), null, 1, new EscalationDef(false, null, true)));
        assertThat(deadlines.runEscalations()).isGreaterThanOrEqualTo(0);
        assertThat(detail("admin", notify.getId()).at("/stages/0/escalatedAt").isNull()).isTrue(); // not due yet

        testClock().advance(Duration.ofHours(3));
        assertThat(deadlines.runEscalations()).isGreaterThanOrEqualTo(2);
        JsonNode n = detail("admin", notify.getId());
        assertThat(n.at("/stages/0/escalatedAt").isNull()).isFalse();
        assertThat(n.at("/approval/status").asText()).isEqualTo("PENDING");
        assertThat(n.get("history").findValuesAsText("type")).containsOnlyOnce("ESCALATED");
        // The approver is reminded, the escalation role (incident managers) and the approver's manager (IT-DB: Kiran) are told.
        assertThat(getJson("farhan.ali", "/api/notifications?size=30").at("/content").findValuesAsText("type")).contains("APPROVAL_ESCALATED");
        assertThat(getJson("sunita.joshi", "/api/notifications?size=50").at("/content").findValuesAsText("title"))
                .anyMatch(t -> t.startsWith("Escalated approval: " + notify.getTitle()));
        JsonNode r = detail("admin", reject.getId());
        assertThat(r.at("/approval/status").asText()).isEqualTo("REJECTED");
        assertThat(r.at("/approval/decisionComment").asText()).contains("deadline passed");

        // Idempotent: a second run does not escalate the same stages again.
        deadlines.runEscalations();
        assertThat(detail("admin", notify.getId()).get("history").findValuesAsText("type")).containsOnlyOnce("ESCALATED");
        mvc.perform(as("priya.raman", post("/api/approvals/escalations/run"))).andExpect(status().isForbidden());
        mvc.perform(as("admin", post("/api/approvals/escalations/run"))).andExpect(status().isOk());
    }

    @Test
    void legacySingleStepApprovalsUseTheEngineAndTheInboxFilters() throws Exception {
        Approval legacy = approvals.request(Approval.Type.AI_ACTION, 1L, "INC-TEST", unique("Legacy"), "details", Map.of(), Role.INCIDENT_MANAGER,
                actor("riya.varma"), false, null);
        JsonNode d = detail("sunita.joshi", legacy.getId());
        assertThat(d.at("/approval/approverRole").asText()).isEqualTo("INCIDENT_MANAGER");
        assertThat(d.at("/stages/0/tasks/0/approverKind").asText()).isEqualTo("ROLE");
        JsonNode list = getJson("sunita.joshi", "/api/approvals?status=PENDING&type=AI_ACTION&search=Legacy&sort=createdAt,desc&size=100");
        assertThat(list.get("content").findValuesAsText("id")).contains(String.valueOf(legacy.getId()));
        assertThat(getJson("riya.varma", "/api/approvals?scope=requested&size=100").get("content").findValuesAsText("id"))
                .contains(String.valueOf(legacy.getId()));
        JsonNode counts = getJson("sunita.joshi", "/api/approvals/counts");
        assertThat(counts.get("pendingTasks").asLong()).isPositive();
        mvc.perform(as("riya.varma", get("/api/approvals?scope=all"))).andExpect(status().isForbidden());
        mvc.perform(as("ananya.bose", get("/api/approvals?scope=all"))).andExpect(status().isOk());
        mvc.perform(as("sunita.joshi", get("/api/approvals?sort=bogus"))).andExpect(status().isBadRequest());
    }

    @Test
    void approvalFlowsAreValidatedAndRestrictedToFlowEditors() throws Exception {
        Map<String, Object> stage = Map.of("name", "Two of one", "mode", "PARALLEL", "quorum", "N", "requiredCount", 2,
                "approvers", List.of(Map.of("kind", "ROLE", "role", "IT_MANAGER")));
        String code = "T-" + unique("F").replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        mvc.perform(jsonBody(as("riya.varma", post("/api/approval-flows")), Map.of("code", code, "name", "x", "stages", List.of(stage))))
                .andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("anil.kumar", post("/api/approval-flows")), Map.of("code", code, "name", "Bad", "stages", List.of(stage))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("stages[0].requiredCount"));
        Map<String, Object> good = Map.of("name", "Manager", "mode", "SEQUENTIAL", "quorum", "ANY", "approvers", List.of(Map.of("kind", "MANAGER")),
                "deadlineHours", 24, "escalation", Map.of("notifyManager", true, "autoReject", false));
        JsonNode flow = body(mvc.perform(jsonBody(as("anil.kumar", post("/api/approval-flows")), Map.of("code", code, "name", "Line manager",
                "stages", List.of(good)))).andExpect(status().isCreated()).andReturn());
        assertThat(getJson("riya.varma", "/api/approval-flows").findValuesAsText("code")).contains(code, "CHANGE_NORMAL", "CHANGE_EMERGENCY");
        long normal = getJson("admin", "/api/approval-flows").findParents("code").stream().filter(f -> f.get("code").asText().equals("CHANGE_NORMAL"))
                .findFirst().orElseThrow().get("id").asLong();
        mvc.perform(as("admin", delete("/api/approval-flows/" + normal))).andExpect(status().isConflict()); // system flow
        mvc.perform(as("anil.kumar", delete("/api/approval-flows/" + flow.get("id").asLong()))).andExpect(status().isNoContent());
        // USER approvers come back with their display names, so editors show people rather than ids.
        long priya = userId("priya.raman");
        Map<String, Object> named = Map.of("name", "Named", "mode", "PARALLEL", "quorum", "ANY",
                "approvers", List.of(Map.of("kind", "USER", "userId", priya), Map.of("kind", "ROLE", "role", "IT_MANAGER")));
        JsonNode namedFlow = body(mvc.perform(jsonBody(as("anil.kumar", post("/api/approval-flows")), Map.of("code", code + "-N", "name", "Named approver",
                "stages", List.of(named)))).andExpect(status().isCreated()).andReturn());
        assertThat(namedFlow.get("userNames").get(String.valueOf(priya)).asText()).isEqualTo("Priya Raman");
        assertThat(getJson("riya.varma", "/api/approval-flows/" + namedFlow.get("id").asLong()).get("userNames").size()).isEqualTo(1);
        mvc.perform(as("anil.kumar", delete("/api/approval-flows/" + namedFlow.get("id").asLong()))).andExpect(status().isNoContent());
        // MANAGER approver: the requester's department manager (Dev Nair, IT-APPS: Priya Raman).
        Approval a = request("dev.nair", Map.of(), new StageDef("Manager", StageMode.PARALLEL, Quorum.ANY, null,
                List.of(new ApproverDef(ApproverKind.MANAGER, null, null, null)), null, null, null));
        assertThat(detail("priya.raman", a.getId()).at("/stages/0/tasks/0/approverUserName").asText()).isEqualTo("Priya Raman");
    }
}

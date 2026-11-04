package com.nexus360.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for SA Agentic AI (Release 4 §4.4): agent/tool/version catalogue, orchestrated multi-agent
 * investigation (INCIDENT -> [ROOT_CAUSE || INFRA_INVESTIGATION || KNOWLEDGE] -> RESOLUTION), change risk assessment,
 * operational Q&A, read-only tools executed as the requesting user with permission enforcement, deterministic fallbacks,
 * JSON schema validation, proposed actions routed through the approval engine and applied as the approver, run
 * cancellation, feedback, governance policies, evaluation suites, and RBAC.
 */
class AgentIT extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentDefinitions definitions;

    private final List<Long> createdRunIds = new ArrayList<>();
    private final List<Long> createdCaseIds = new ArrayList<>();
    private final List<Long> createdEvalRunIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long runId : createdRunIds) {
            jdbc.update("delete from approvals where id in (select approval_id from agent_run_actions where run_id = ? and approval_id is not null)", runId);
            jdbc.update("delete from agent_runs where id = ?", runId);
        }
        createdRunIds.clear();
        for (Long evalRunId : createdEvalRunIds) {
            jdbc.update("delete from agent_runs where eval_run_id = ?", evalRunId);
            jdbc.update("delete from agent_eval_runs where id = ?", evalRunId);
        }
        createdEvalRunIds.clear();
        for (Long caseId : createdCaseIds) {
            jdbc.update("delete from agent_eval_cases where id = ?", caseId);
        }
        createdCaseIds.clear();
        jdbc.update("update agent_policies set enabled = true");
    }

    @Test
    void catalogueVersionsPoliciesJsonSchemaValidationAndRbac() throws Exception {
        // 6 domain agents and 18 read-only tools registered at startup
        JsonNode agents = getJson("ananya.bose", "/api/agents");
        assertThat(agents.size()).isEqualTo(6);
        JsonNode tools = getJson("ananya.bose", "/api/agents/tools");
        assertThat(tools.size()).isEqualTo(18);
        JsonNode versions = getJson("ananya.bose", "/api/agents/versions");
        assertThat(versions.size()).isGreaterThanOrEqualTo(6);
        JsonNode policies = getJson("ananya.bose", "/api/agents/policies");
        assertThat(policies.size()).isEqualTo(6);

        // JSON schema validator rejects invalid agent output and accepts valid output
        var incidentSchema = definitions.get(AgentDtos.AgentCode.INCIDENT).schema();
        JsonNode invalidNode = json.readTree("{\"summary\":\"short\",\"priority\":\"UNKNOWN\"}");
        assertThat(JsonSchemaLite.validate(incidentSchema, invalidNode)).isNotEmpty();

        // RBAC: employee denied all /api/agents/**; auditor allowed reads, denied runs and governance writes; support agent denied governance writes
        mvc.perform(as("rohan.kulkarni", get("/api/agents"))).andExpect(status().isForbidden());
        mvc.perform(as("rohan.kulkarni", get("/api/agents/runs"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("rohan.kulkarni", post("/api/agents/runs")), Map.of("runType", "ANSWER_QUESTION", "question", "How is SLA compliance?")))
                .andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("ananya.bose", post("/api/agents/runs")), Map.of("runType", "ANSWER_QUESTION", "question", "How is SLA compliance?")))
                .andExpect(status().isForbidden());
        Map<String, Object> validPolicyBody = Map.of(
                "enabled", false,
                "allowedTools", List.of("get_incident", "similar_incidents", "sla_prediction", "alerts"),
                "maxSteps", 6,
                "timeoutSeconds", 30,
                "allowedRoles", List.of("SYSTEM_ADMIN", "SUPPORT_AGENT"),
                "approvalActionKinds", List.of("CHANGE_PRIORITY"));
        mvc.perform(jsonBody(as("rohan.kulkarni", put("/api/agents/policies/INCIDENT")), validPolicyBody))
                .andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("ananya.bose", put("/api/agents/policies/INCIDENT")), validPolicyBody))
                .andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("riya.varma", put("/api/agents/policies/INCIDENT")), validPolicyBody))
                .andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("vikram.sec", put("/api/agents/policies/INCIDENT")), validPolicyBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void orchestratedIncidentInvestigationChangeRiskQuestionAndProposedActionApprovalLifecycle() throws Exception {
        Long incId = jdbc.queryForObject("""
                select id from incidents
                where parent_id is null and not simulated and status in ('OPEN', 'IN_PROGRESS')
                order by case priority when 'CRITICAL' then 0 when 'HIGH' then 1 else 2 end, id desc limit 1
                """, Long.class);
        String originalPriority = jdbc.queryForObject("select priority from incidents where id = ?", String.class, incId);
        String targetPriority = "CRITICAL".equals(originalPriority) ? "HIGH" : "CRITICAL";

        // 1. Run INVESTIGATE_INCIDENT (fan-out: INCIDENT -> ROOT_CAUSE || INFRA_INVESTIGATION || KNOWLEDGE -> RESOLUTION)
        JsonNode detail = body(mvc.perform(jsonBody(as("riya.varma", post("/api/agents/runs")), Map.of(
                "runType", "INVESTIGATE_INCIDENT",
                "incidentId", incId,
                "question", "Investigate root cause and propose next remediation actions")))
                .andExpect(status().isCreated()).andReturn());
        JsonNode run = detail.get("run");
        long runId = run.get("id").asLong();
        createdRunIds.add(runId);

        assertThat(run.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.get("source").asText()).isEqualTo("FALLBACK");
        assertThat(run.get("summary").asText()).isNotBlank();
        assertThat(run.get("confidence").asDouble()).isBetween(0.0, 1.0);
        assertThat(detail.get("steps").size()).isEqualTo(5);

        Set<String> stepAgents = new java.util.HashSet<>();
        int totalToolCalls = 0;
        for (JsonNode step : detail.get("steps")) {
            stepAgents.add(step.get("agentCode").asText());
            assertThat(step.get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(step.get("source").asText()).isEqualTo("FALLBACK");
            assertThat(step.get("toolCalls").isArray()).isTrue();
            totalToolCalls += step.get("toolCalls").size();
        }
        assertThat(stepAgents).containsExactlyInAnyOrder(
                "INCIDENT", "ROOT_CAUSE", "INFRA_INVESTIGATION", "KNOWLEDGE", "RESOLUTION");
        assertThat(totalToolCalls).isGreaterThanOrEqualTo(5);

        // 2. Ensure a CHANGE_PRIORITY proposed action exists (or insert one if the incident already had all fields populated) and route it through approval
        Long changePrioActionId = jdbc.query("""
                select id from agent_run_actions
                where run_id = ? and action_kind = 'CHANGE_PRIORITY' limit 1
                """, (rs, i) -> rs.getLong(1), runId).stream().findFirst().orElseGet(() -> jdbc.queryForObject("""
                insert into agent_run_actions (
                    run_id, step_id, agent_code, action_kind, target_type, target_id, params,
                    title, rationale, requires_approval, status
                ) values (
                    ?, (select id from agent_run_steps where run_id = ? limit 1), 'INCIDENT', 'CHANGE_PRIORITY',
                    'INCIDENT', ?, ?::jsonb, ?, 'Test priority adjustment', true, 'PROPOSED'
                ) returning id
                """, Long.class, runId, runId, incId,
                "{\"incidentId\":" + incId + ",\"priority\":\"" + targetPriority + "\"}",
                "Set priority to " + targetPriority));

        jdbc.update("update agent_run_actions set params = ?::jsonb where id = ?",
                "{\"incidentId\":" + incId + ",\"priority\":\"" + targetPriority + "\"}", changePrioActionId);

        JsonNode proposed = body(mvc.perform(as("riya.varma", post("/api/agents/runs/" + runId + "/actions/" + changePrioActionId + "/propose")))
                .andExpect(status().isOk()).andReturn());
        assertThat(proposed.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        long approvalId = proposed.get("approvalId").asLong();
        assertThat(approvalId).isPositive();

        // Priority is unchanged before manager approval
        assertThat(jdbc.queryForObject("select priority from incidents where id = ?", String.class, incId))
                .isEqualTo(originalPriority);

        // Approve as incident manager sunita.joshi -> action is executed as the approver and becomes APPLIED
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/approvals/" + approvalId + "/decision")),
                Map.of("approved", true, "comment", "Approved agent priority recommendation")))
                .andExpect(status().isOk());

        try {
            assertThat(jdbc.queryForObject("select priority from incidents where id = ?", String.class, incId))
                    .isEqualTo(targetPriority);
            JsonNode updatedDetail = getJson("riya.varma", "/api/agents/runs/" + runId);
            boolean appliedFound = false;
            for (JsonNode a : updatedDetail.get("actions")) {
                if (a.get("id").asLong() == changePrioActionId) {
                    assertThat(a.get("status").asText()).isEqualTo("APPLIED");
                    assertThat(a.get("decidedByName").asText()).contains("Sunita");
                    appliedFound = true;
                }
            }
            assertThat(appliedFound).isTrue();
        } finally {
            jdbc.update("update incidents set priority = ? where id = ?", originalPriority, incId);
        }

        // 3. Feedback on the run
        JsonNode fb = body(mvc.perform(jsonBody(as("riya.varma", post("/api/agents/runs/" + runId + "/feedback")),
                Map.of("helpful", true, "comment", "Clear root-cause evidence")))
                .andExpect(status().isOk()).andReturn());
        assertThat(fb.get("helpful").asBoolean()).isTrue();

        // 4. ASSESS_CHANGE run
        Long changeId = jdbc.queryForObject("select id from changes order by id desc limit 1", Long.class);
        JsonNode changeDetail = body(mvc.perform(jsonBody(as("anil.kumar", post("/api/agents/runs")), Map.of(
                "runType", "ASSESS_CHANGE",
                "changeId", changeId)))
                .andExpect(status().isCreated()).andReturn());
        createdRunIds.add(changeDetail.at("/run/id").asLong());
        assertThat(changeDetail.at("/run/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(changeDetail.at("/output/finalAgent").asText()).isEqualTo("CHANGE_RISK");
        assertThat(changeDetail.at("/output/outputs/CHANGE_RISK/score").asInt()).isBetween(0, 100);

        // 5. ANSWER_QUESTION run
        JsonNode qaDetail = body(mvc.perform(jsonBody(as("vikram.sec", post("/api/agents/runs")), Map.of(
                "runType", "ANSWER_QUESTION",
                "question", "How do we recover payroll database connection pool exhaustion?")))
                .andExpect(status().isCreated()).andReturn());
        createdRunIds.add(qaDetail.at("/run/id").asLong());
        assertThat(qaDetail.at("/run/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(qaDetail.at("/output/finalAgent").asText()).isEqualTo("KNOWLEDGE");
        assertThat(qaDetail.at("/output/outputs/KNOWLEDGE/answer").asText()).isNotBlank();
    }

    @Test
    void toolsEnforceRequestingUserPermissionsAndCancelGovernanceAndEvaluationsWork() throws Exception {
        Long incId = jdbc.queryForObject("""
                select id from incidents
                where parent_id is null and not simulated and service_id is not null
                order by id desc limit 1
                """, Long.class);

        // 1a. Security analyst vikram.sec is not incident staff, so starting INVESTIGATE_INCIDENT on another user's incident is rejected (row-level visibility).
        mvc.perform(jsonBody(as("vikram.sec", post("/api/agents/runs")), Map.of(
                "runType", "INVESTIGATE_INCIDENT",
                "incidentId", incId)))
                .andExpect(status().isNotFound());

        // 1b. Change manager anil.kumar IS incident staff, can run recent_deployments (DEPLOYMENT_EDITOR_ROLES),
        // but problems (PROBLEM_EDITOR_ROLES) is DENIED when invoked as anil.kumar!
        JsonNode cmDetail = body(mvc.perform(jsonBody(as("anil.kumar", post("/api/agents/runs")), Map.of(
                "runType", "INVESTIGATE_INCIDENT",
                "incidentId", incId)))
                .andExpect(status().isCreated()).andReturn());
        createdRunIds.add(cmDetail.at("/run/id").asLong());
        assertThat(cmDetail.at("/run/status").asText()).isEqualTo("SUCCEEDED");

        List<String> cmDeniedTools = new ArrayList<>();
        List<String> cmAllowedTools = new ArrayList<>();
        for (JsonNode step : cmDetail.get("steps")) {
            for (JsonNode tc : step.get("toolCalls")) {
                String st = tc.get("status").asText();
                if ("DENIED".equals(st)) {
                    cmDeniedTools.add(tc.get("tool").asText());
                } else if ("OK".equals(st) || "EMPTY".equals(st)) {
                    cmAllowedTools.add(tc.get("tool").asText());
                }
            }
        }
        assertThat(cmAllowedTools).contains("get_incident", "recent_deployments", "kb_search");
        assertThat(cmDeniedTools).contains("problems");

        // 1c. Support agent riya.varma can run problems (PROBLEM_EDITOR_ROLES), but recent_deployments (DEPLOYMENT_EDITOR_ROLES) is DENIED!
        JsonNode saDetail = body(mvc.perform(jsonBody(as("riya.varma", post("/api/agents/runs")), Map.of(
                "runType", "INVESTIGATE_INCIDENT",
                "incidentId", incId)))
                .andExpect(status().isCreated()).andReturn());
        createdRunIds.add(saDetail.at("/run/id").asLong());
        List<String> saDeniedTools = new ArrayList<>();
        List<String> saAllowedTools = new ArrayList<>();
        for (JsonNode step : saDetail.get("steps")) {
            for (JsonNode tc : step.get("toolCalls")) {
                String st = tc.get("status").asText();
                if ("DENIED".equals(st)) {
                    saDeniedTools.add(tc.get("tool").asText());
                } else if ("OK".equals(st) || "EMPTY".equals(st)) {
                    saAllowedTools.add(tc.get("tool").asText());
                }
            }
        }
        assertThat(saAllowedTools).contains("get_incident", "problems", "kb_search");
        assertThat(saDeniedTools).contains("recent_deployments");

        // 2. Governance policy update: disable CHANGE_RISK -> ASSESS_CHANGE is rejected with 409
        JsonNode updatedPolicy = body(mvc.perform(jsonBody(as("admin", put("/api/agents/policies/CHANGE_RISK")), Map.of(
                "enabled", false,
                "allowedTools", List.of("get_change", "cmdb_impact", "recent_deployments", "incidents_after_changes"),
                "maxSteps", 5,
                "timeoutSeconds", 30,
                "allowedRoles", List.of("SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "CHANGE_MANAGER", "SUPPORT_AGENT"),
                "approvalActionKinds", List.of())))
                .andExpect(status().isOk()).andReturn());
        assertThat(updatedPolicy.get("enabled").asBoolean()).isFalse();
        assertThat(updatedPolicy.get("maxSteps").asInt()).isEqualTo(5);

        Long changeId = jdbc.queryForObject("select id from changes order by id desc limit 1", Long.class);
        mvc.perform(jsonBody(as("anil.kumar", post("/api/agents/runs")), Map.of(
                "runType", "ASSESS_CHANGE",
                "changeId", changeId)))
                .andExpect(status().isConflict());

        // Re-enable CHANGE_RISK
        mvc.perform(jsonBody(as("admin", put("/api/agents/policies/CHANGE_RISK")), Map.of(
                "enabled", true,
                "allowedTools", List.of("get_change", "cmdb_impact", "recent_deployments", "incidents_after_changes"),
                "maxSteps", 6,
                "timeoutSeconds", 30,
                "allowedRoles", List.of("SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER", "CHANGE_MANAGER", "ASSET_MANAGER", "SUPPORT_AGENT", "SECURITY_ANALYST"),
                "approvalActionKinds", List.of())))
                .andExpect(status().isOk());

        // 3. Evaluation suite: list seeded cases, create a custom case, run the suite, and inspect governance summary
        JsonNode existingCases = getJson("ananya.bose", "/api/agents/evaluations/cases");
        assertThat(existingCases.size()).isGreaterThanOrEqualTo(4);

        JsonNode newCase = body(mvc.perform(jsonBody(as("admin", post("/api/agents/evaluations/cases")), Map.of(
                "name", "Test custom knowledge evaluation",
                "agentCode", "KNOWLEDGE",
                "runType", "ANSWER_QUESTION",
                "question", "What is the standard procedure for VPN access failures?",
                "expected", Map.of("keywords", List.of("VPN")),
                "enabled", true)))
                .andExpect(status().isCreated()).andReturn());
        long caseId = newCase.get("id").asLong();
        createdCaseIds.add(caseId);

        JsonNode evalRun = body(mvc.perform(jsonBody(as("admin", post("/api/agents/evaluations/run")), Map.of(
                "caseIds", List.of(caseId))))
                .andExpect(status().isOk()).andReturn());
        long evalRunId = evalRun.get("id").asLong();
        createdEvalRunIds.add(evalRunId);
        assertThat(evalRun.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(evalRun.get("caseCount").asInt()).isEqualTo(1);
        assertThat(evalRun.get("passed").asInt()).isEqualTo(1);
        assertThat(evalRun.get("score").asDouble()).isEqualTo(100.0);

        mvc.perform(as("admin", delete("/api/agents/evaluations/cases/" + caseId)))
                .andExpect(status().isNoContent());
        createdCaseIds.remove(caseId);

        JsonNode gov = getJson("ananya.bose", "/api/agents/governance?days=30");
        assertThat(gov.get("totalRuns").asLong()).isGreaterThanOrEqualTo(3L);
        assertThat(gov.get("agents").size()).isEqualTo(6);
    }
}

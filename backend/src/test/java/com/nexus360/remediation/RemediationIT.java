package com.nexus360.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class RemediationIT extends IntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void rbacAndExecutionSystemSafetyGuardrails() throws Exception {
    // Employee denied read & write
    mvc.perform(as("rohan.kulkarni", get("/api/remediation/meta")))
        .andExpect(status().isForbidden());
    mvc.perform(as("rohan.kulkarni", get("/api/remediation/systems")))
        .andExpect(status().isForbidden());

    // Auditor allowed read, denied write
    mvc.perform(as("ananya.bose", get("/api/remediation/meta")))
        .andExpect(status().isOk());
    mvc.perform(as("ananya.bose", get("/api/remediation/systems")))
        .andExpect(status().isOk());
    mvc.perform(
            jsonBody(
                as("ananya.bose", post("/api/remediation/systems")),
                Map.of("name", "Forbidden System", "kind", "SIMULATED")))
        .andExpect(status().isForbidden());

    // Only SYSTEM_ADMIN can create execution systems (IT_ADMIN denied)
    mvc.perform(
            jsonBody(
                as("rajesh.iyer", post("/api/remediation/systems")),
                Map.of("name", "IT Admin System", "kind", "SIMULATED")))
        .andExpect(status().isForbidden());

    // SSRF guard rejects loopback and metadata URLs
    mvc.perform(
            jsonBody(
                as("admin", post("/api/remediation/systems")),
                Map.of(
                    "name", "Loopback Webhook",
                    "kind", "HTTP_WEBHOOK",
                    "endpointUrl", "http://127.0.0.1:8080/hook")))
        .andExpect(status().isBadRequest());

    mvc.perform(
            jsonBody(
                as("admin", post("/api/remediation/systems")),
                Map.of(
                    "name", "Metadata SSRF",
                    "kind", "HTTP_WEBHOOK",
                    "endpointUrl", "http://169.254.169.254/latest/meta-data")))
        .andExpect(status().isBadRequest());

    // Rejects shell/SQL keywords in action templates
    mvc.perform(
            jsonBody(
                as("admin", post("/api/remediation/systems")),
                Map.of(
                    "name", "Unsafe Template System",
                    "kind", "SIMULATED",
                    "actionTemplates",
                        List.of(
                            Map.of(
                                "id", "bad-tpl",
                                "name", "Run bash -c rm -rf /",
                                "description", "DROP TABLE users")))))
        .andExpect(status().isBadRequest());

    // Valid system creation encrypts credentials and never returns raw/encrypted secrets
    MvcResult sysRes =
        mvc.perform(
                jsonBody(
                    as("admin", post("/api/remediation/systems")),
                    Map.of(
                        "name", "Ansible AWX Controller",
                        "kind", "ANSIBLE_AWX",
                        "endpointUrl", "https://awx.internal.nexus360.example.com/api/v2",
                        "credentials", "awx_secret_token_999",
                        "credentialScope", "org:ops",
                        "actionTemplates",
                            List.of(
                                Map.of(
                                    "id", "restart-worker",
                                    "name", "Restart Worker Service",
                                    "description", "Gracefully restarts worker pod",
                                    "parametersSchema", Map.of("graceSeconds", "integer"),
                                    "targetScope", "SERVICE")))))
            .andExpect(status().isCreated())
            .andReturn();

    String rawJson = sysRes.getResponse().getContentAsString();
    assertThat(rawJson).doesNotContain("awx_secret_token_999");
    assertThat(rawJson).doesNotContain("encryptedCredentials");
    JsonNode sysNode = json.readTree(rawJson);
    long sysId = sysNode.get("id").asLong();
    assertThat(sysNode.get("credentialsConfigured").asBoolean()).isTrue();
    assertThat(sysNode.get("status").asText()).isEqualTo("NOT_CONNECTED");

    // Test connection on external unverified adapter stays NOT_CONNECTED
    MvcResult testRes =
        mvc.perform(as("admin", post("/api/remediation/systems/" + sysId + "/test")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(testRes.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo("NOT_CONNECTED");
  }

  @Test
  void playbookVersioningPolicyChecksOverrideRollbackAndTamperEvidentAudit() throws Exception {
    Long simSysId =
        jdbc.queryForObject(
            "SELECT id FROM execution_systems WHERE kind = 'SIMULATED' ORDER BY id LIMIT 1",
            Long.class);
    Long incidentId =
        jdbc.queryForObject(
            "SELECT id FROM incidents WHERE NOT simulated ORDER BY id LIMIT 1",
            Long.class);

    // 1. Create playbook (v1 DRAFT) as CHANGE_MANAGER (anil.kumar)
    Map<String, Object> createPb =
        Map.of(
            "code", "PB-TEST-ROLLBACK-01",
            "name", "Test Multi-Step Remediation with Rollback",
            "description", "Validates policy checks, override, and reverse rollback on failure.",
            "targetEnvironment", "PRODUCTION",
            "preApprovedAlertTypes", List.of("DB_POOL_SATURATION"),
            "maxBlastRadius", 10,
            "rateLimitPerHour", 5,
            "requireLinkedRecord", true,
            "triggerKinds", List.of("MANUAL", "AGENT"),
            "steps",
                List.of(
                    Map.of(
                        "stepOrder", 1,
                        "name", "Scale Connection Pool",
                        "kind", "ACTION",
                        "systemId", simSysId,
                        "templateId", "drain-idle-connections",
                        "parameters", Map.of("maxIdleSeconds", 30),
                        "rollbackTemplateId", "restore-pool-defaults",
                        "rollbackParameters", Map.of("mode", "baseline")),
                    Map.of(
                        "stepOrder", 2,
                        "name", "Drain Secondary Replicas",
                        "kind", "ACTION",
                        "systemId", simSysId,
                        "templateId", "drain-idle-connections",
                        "parameters", Map.of("maxIdleSeconds", 15),
                        "rollbackTemplateId", "restore-pool-defaults",
                        "rollbackParameters", Map.of("mode", "replica-baseline")),
                    Map.of(
                        "stepOrder", 3,
                        "name", "Verify Latency Normalized",
                        "kind", "VERIFY",
                        "systemId", simSysId,
                        "templateId", "verify-pool",
                        "parameters", Map.of("below", 40))));

    MvcResult pbRes =
        mvc.perform(jsonBody(as("anil.kumar", post("/api/remediation/playbooks")), createPb))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode pb = json.readTree(pbRes.getResponse().getContentAsString());
    long pbId = pb.get("id").asLong();
    assertThat(pb.get("versions").get(0).get("status").asText()).isEqualTo("DRAFT");

    // 2. Submit v1 for approval
    MvcResult subRes =
        mvc.perform(
                as("anil.kumar", post("/api/remediation/playbooks/" + pbId + "/versions/1/submit")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(subRes.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo("IN_REVIEW");

    // 3. Approve v1 as IT_MANAGER (priya.raman)
    MvcResult appRes =
        mvc.perform(
                as(
                    "priya.raman",
                    post("/api/remediation/playbooks/" + pbId + "/versions/1/approve")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(appRes.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo("APPROVED");

    // 4. Policy check blocks run when requireLinkedRecord=true and no incidentId/changeId is given
    MvcResult blockedRes =
        mvc.perform(
                jsonBody(
                    as("riya.varma", post("/api/remediation/runs")),
                    Map.of(
                        "playbookId", pbId,
                        "targetKey", "payroll-db-primary",
                        "environment", "STAGING",
                        "triggerKind", "MANUAL")))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode blockedDetail = json.readTree(blockedRes.getResponse().getContentAsString());
    long blockedRunId = blockedDetail.get("run").get("id").asLong();
    assertThat(blockedDetail.get("run").get("status").asText()).isEqualTo("BLOCKED");

    // 5. Override the BLOCKED run as PLAYBOOK_APPROVER (priya.raman) -> executes and SUCCEEDS
    MvcResult overrideRes =
        mvc.perform(
                jsonBody(
                    as("priya.raman", post("/api/remediation/runs/" + blockedRunId + "/override")),
                    Map.of("reason", "Emergency staging remediation approved by IT Manager")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode overriddenDetail = json.readTree(overrideRes.getResponse().getContentAsString());
    assertThat(overriddenDetail.get("run").get("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(overriddenDetail.get("run").get("overriddenByName").asText()).isNotBlank();

    // 6. Execute run linked to incident with simulateFailureAtStep=2 -> triggers reverse rollback!
    MvcResult failRunRes =
        mvc.perform(
                jsonBody(
                    as("priya.raman", post("/api/remediation/runs")),
                    Map.of(
                        "playbookId", pbId,
                        "targetKey", "payroll-app-01",
                        "environment", "PRODUCTION",
                        "incidentId", incidentId,
                        "triggerKind", "MANUAL",
                        "simulateFailureAtStep", 2,
                        "overrideReason", "Approved production execution test")))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode failDetail = json.readTree(failRunRes.getResponse().getContentAsString());
    assertThat(failDetail.get("run").get("status").asText()).isEqualTo("ROLLED_BACK");

    boolean hasRollbackStep = false;
    for (JsonNode step : failDetail.get("steps")) {
      if (step.get("isRollback").asBoolean()) {
        hasRollbackStep = true;
        assertThat(step.get("status").asText()).isEqualTo("SUCCEEDED");
      }
    }
    assertThat(hasRollbackStep).isTrue();

    // Verify incident comment was posted
    Long commentCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM incident_comments WHERE incident_id = ? AND body LIKE '%[Remediation Playbook%'",
            Long.class,
            incidentId);
    assertThat(commentCount).isPositive();

    // 7. Verify tamper-evident hash chain
    MvcResult verifyRes =
        mvc.perform(as("ananya.bose", get("/api/remediation/audit/verify")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode verifyNode = json.readTree(verifyRes.getResponse().getContentAsString());
    assertThat(verifyNode.get("valid").asBoolean()).isTrue();
    assertThat(verifyNode.get("totalRecords").asLong()).isGreaterThanOrEqualTo(4L);

    // 8. Prove database trigger blocks UPDATE and DELETE on remediation_audit
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE remediation_audit SET event_type = 'TAMPERED' WHERE id = (SELECT MIN(id) FROM remediation_audit)"))
        .hasMessageContaining("remediation_audit is append-only and tamper-evident");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM remediation_audit WHERE id = (SELECT MIN(id) FROM remediation_audit)"))
        .hasMessageContaining("remediation_audit is append-only and tamper-evident");
  }
}

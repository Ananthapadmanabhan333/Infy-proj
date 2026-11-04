package com.nexus360.secops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class SecOpsIT extends IntegrationTest {

  @AfterEach
  void disableSim() throws Exception {
    mvc.perform(jsonBody(as("admin", put("/api/simulation/mode")), Map.of("enabled", false)))
        .andExpect(status().isOk());
  }

  @Test
  void rbacAndRestrictedSecurityIncidentVisibility() throws Exception {
    // Employee denied read and write
    mvc.perform(as("rohan.kulkarni", get("/api/security/overview")))
        .andExpect(status().isForbidden());
    mvc.perform(as("rohan.kulkarni", get("/api/security/incidents")))
        .andExpect(status().isForbidden());
    mvc.perform(
            jsonBody(
                as("rohan.kulkarni", post("/api/security/incidents")),
                Map.of("title", "Unauthorised", "category", "PHISHING", "severity", "HIGH")))
        .andExpect(status().isForbidden());

    // Support agent (non-SECOPS) denied
    mvc.perform(as("riya.varma", get("/api/security/incidents")))
        .andExpect(status().isForbidden());

    // Auditor allowed read, denied write
    mvc.perform(as("ananya.bose", get("/api/security/overview")))
        .andExpect(status().isOk());
    mvc.perform(as("ananya.bose", get("/api/security/templates")))
        .andExpect(status().isOk());
    mvc.perform(
            jsonBody(
                as("ananya.bose", post("/api/security/incidents")),
                Map.of("title", "Auditor write attempt", "category", "PHISHING", "severity", "HIGH")))
        .andExpect(status().isForbidden());

    // Security analyst allowed read & write
    mvc.perform(as("vikram.sec", get("/api/security/overview")))
        .andExpect(status().isOk());
  }

  @Test
  void vulnerabilityIngestionFormatsDeduplicationFullScanAndRiskAcceptance() throws Exception {
    // Create source as SECURITY_ANALYST (vikram.sec) -> rawToken returned once
    MvcResult srcRes =
        mvc.perform(
                jsonBody(
                    as("vikram.sec", post("/api/security/vulnerabilities/sources")),
                    Map.of(
                        "name", "CI Security Scanner",
                        "format", "GENERIC_JSON",
                        "scannerName", "Trivy-CI")))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode srcCreated = json.readTree(srcRes.getResponse().getContentAsString());
    long sourceId = srcCreated.get("source").get("id").asLong();
    String rawToken = srcCreated.get("rawToken").asText();
    assertThat(rawToken).startsWith("vsrc_");

    // GET sources never returns rawToken or tokenHash
    MvcResult listSrcRes =
        mvc.perform(as("ananya.bose", get("/api/security/vulnerabilities/sources")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(listSrcRes.getResponse().getContentAsString()).doesNotContain(rawToken);

    // Invalid token without JWT -> 401
    mvc.perform(
            jsonBody(
                post("/api/security/vulnerabilities/ingest")
                    .header("X-SA-Source-Token", "vsrc_invalid_token"),
                Map.of("sourceId", sourceId, "format", "GENERIC_JSON", "payload", List.of())))
        .andExpect(status().isUnauthorized());

    // 1. Ingest GENERIC_JSON using X-SA-Source-Token
    List<Map<String, Object>> genericPayload =
        List.of(
            Map.of(
                "cveId", "CVE-2026-1001",
                "cweId", "CWE-89",
                "title", "SQL Injection in Legacy Report Adapter",
                "assetName", "payroll-db-primary",
                "packageName", "org.postgresql:postgresql",
                "installedVersion", "42.5.0",
                "fixedVersion", "42.7.3",
                "cvssBase", 9.8,
                "internetExposed", true,
                "exploitKnown", true),
            Map.of(
                "cveId", "CVE-2026-1002",
                "cweId", "CWE-200",
                "title", "Minor Info Header Leak",
                "assetName", "hr-portal-web",
                "packageName", "nginx",
                "installedVersion", "1.24.0",
                "fixedVersion", "1.26.1",
                "cvssBase", 3.7,
                "internetExposed", false,
                "exploitKnown", false));

    MvcResult genRes =
        mvc.perform(
                jsonBody(
                    post("/api/security/vulnerabilities/ingest")
                        .header("X-SA-Source-Token", rawToken),
                    Map.of(
                        "sourceId", sourceId,
                        "format", "GENERIC_JSON",
                        "fullScan", false,
                        "payload", genericPayload)))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode genNode = json.readTree(genRes.getResponse().getContentAsString());
    assertThat(genNode.get("ingested").asInt()).isEqualTo(2);
    assertThat(genNode.get("updated").asInt()).isZero();

    // Re-ingest same finding -> deduplicated (updated = 1)
    MvcResult dedupeRes =
        mvc.perform(
                jsonBody(
                    post("/api/security/vulnerabilities/ingest")
                        .header("X-SA-Source-Token", rawToken),
                    Map.of(
                        "sourceId", sourceId,
                        "format", "GENERIC_JSON",
                        "fullScan", false,
                        "payload", List.of(genericPayload.get(0)))))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(dedupeRes.getResponse().getContentAsString()).get("updated").asInt())
        .isEqualTo(1);

    // 2. Ingest SARIF 2.1.0
    Map<String, Object> sarifPayload =
        Map.of(
            "version", "2.1.0",
            "runs",
                List.of(
                    Map.of(
                        "results",
                            List.of(
                                Map.of(
                                    "ruleId", "CVE-2026-2002",
                                    "message", Map.of("text", "SARIF RCE in Spring Expression"),
                                    "locations",
                                        List.of(
                                            Map.of(
                                                "physicalLocation",
                                                    Map.of(
                                                        "artifactLocation",
                                                            Map.of("uri", "payroll-app-01")))),
                                    "properties",
                                        Map.of(
                                            "packageName", "org.springframework:spring-expression",
                                            "cvssBase", 8.8,
                                            "internetExposed", true,
                                            "exploitKnown", true))))));
    MvcResult sarifRes =
        mvc.perform(
                jsonBody(
                    as("vikram.sec", post("/api/security/vulnerabilities/ingest")),
                    Map.of(
                        "sourceId", sourceId,
                        "format", "SARIF",
                        "fullScan", false,
                        "payload", sarifPayload)))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(sarifRes.getResponse().getContentAsString()).get("ingested").asInt())
        .isEqualTo(1);

    // 3. Ingest TRIVY_JSON
    Map<String, Object> trivyPayload =
        Map.of(
            "Results",
                List.of(
                    Map.of(
                        "Target", "payroll-app-02",
                        "Vulnerabilities",
                            List.of(
                                Map.of(
                                    "VulnerabilityID", "CVE-2026-3003",
                                    "Title", "Trivy Netty HTTP2 Rapid Reset",
                                    "PkgName", "io.netty:netty-codec-http2",
                                    "InstalledVersion", "4.1.99",
                                    "FixedVersion", "4.1.108",
                                    "CVSS", Map.of("nvd", Map.of("V3Score", 7.5)))))));
    MvcResult trivyRes =
        mvc.perform(
                jsonBody(
                    as("vikram.sec", post("/api/security/vulnerabilities/ingest")),
                    Map.of(
                        "sourceId", sourceId,
                        "format", "TRIVY_JSON",
                        "fullScan", false,
                        "payload", trivyPayload)))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(trivyRes.getResponse().getContentAsString()).get("ingested").asInt())
        .isEqualTo(1);

    // 4. Ingest CSV with fullScan=true -> only keeps the CSV row, auto-closing the previous 4 findings!
    String csvPayload =
        "cveId,title,assetName,packageName,installedVersion,fixedVersion,cvssBase,internetExposed,exploitKnown\n"
            + "CVE-2026-4004,OpenSSL Buffer Overflow,vpn-gw-01,openssl,3.0.1,3.0.14,9.6,true,true\n";
    MvcResult csvRes =
        mvc.perform(
                jsonBody(
                    as("vikram.sec", post("/api/security/vulnerabilities/ingest")),
                    Map.of(
                        "sourceId", sourceId,
                        "format", "CSV",
                        "fullScan", true,
                        "payload", csvPayload)))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode csvNode = json.readTree(csvRes.getResponse().getContentAsString());
    assertThat(csvNode.get("ingested").asInt()).isEqualTo(1);
    assertThat(csvNode.get("autoClosed").asInt()).isEqualTo(4);

    // Verify findings list and accept risk on CVE-2026-4004
    MvcResult findingsRes =
        mvc.perform(as("vikram.sec", get("/api/security/vulnerabilities/findings?status=OPEN")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode findings = json.readTree(findingsRes.getResponse().getContentAsString());
    assertThat(findings.size()).isGreaterThanOrEqualTo(1);
    long findingId = findings.get(0).get("id").asLong();
    assertThat(findings.get(0).get("riskBand").asText()).isEqualTo("CRITICAL");

    MvcResult acceptRes =
        mvc.perform(
                jsonBody(
                    as(
                        "vikram.sec",
                        post("/api/security/vulnerabilities/findings/" + findingId + "/accept-risk")),
                    Map.of(
                        "reason", "Mitigated by upstream WAF rule while awaiting maintenance window",
                        "acceptedUntil", Instant.now().plus(14, ChronoUnit.DAYS).toString())))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode accepted = json.readTree(acceptRes.getResponse().getContentAsString());
    assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
    assertThat(accepted.get("approvalId").asLong()).isPositive();
  }

  @Test
  void securityIncidentLifecycleCorrelationAndVulnWaveSimulation() throws Exception {
    // 1. Create security incident from template PHISHING_RESPONSE
    MvcResult createRes =
        mvc.perform(
                jsonBody(
                    as("vikram.sec", post("/api/security/incidents")),
                    Map.of(
                        "title", "Spear-phishing campaign against Finance Payroll team",
                        "description", "Credential harvesting link reported by 3 employees.",
                        "category", "PHISHING",
                        "severity", "HIGH",
                        "affectedUserEmail", "kavya.reddy@nexus360.local",
                        "templateCode", "PHISHING_RESPONSE")))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode detail = json.readTree(createRes.getResponse().getContentAsString());
    long secIncId = detail.get("incident").get("id").asLong();
    assertThat(detail.get("tasks").size()).isGreaterThanOrEqualTo(3);
    long firstTaskId = detail.get("tasks").get(0).get("id").asLong();

    // Complete task
    MvcResult taskRes =
        mvc.perform(
                as(
                    "vikram.sec",
                    post(
                        "/api/security/incidents/"
                            + secIncId
                            + "/tasks/"
                            + firstTaskId
                            + "/complete")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(
            json
                .readTree(taskRes.getResponse().getContentAsString())
                .get("tasks")
                .get(0)
                .get("status")
                .asText())
        .isEqualTo("COMPLETED");

    // Add evidence note + IOC
    MvcResult noteRes =
        mvc.perform(
                jsonBody(
                    as("vikram.sec", post("/api/security/incidents/" + secIncId + "/notes")),
                    Map.of(
                        "note", "Blocked sender domain at mail gateway.",
                        "indicatorType", "DOMAIN",
                        "indicatorValue", "login-payroll-support.example.net",
                        "indicatorConfidence", "HIGH")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode withNote = json.readTree(noteRes.getResponse().getContentAsString()).get("incident");
    assertThat(withNote.get("evidenceNotes").size()).isEqualTo(1);
    assertThat(withNote.get("indicators").size()).isEqualTo(1);

    // Transition NEW -> TRIAGE -> CONTAINMENT -> ERADICATION -> RECOVERY -> CLOSED
    for (String nextStatus : List.of("TRIAGE", "CONTAINMENT", "ERADICATION", "RECOVERY")) {
      mvc.perform(
              jsonBody(
                  as("vikram.sec", post("/api/security/incidents/" + secIncId + "/transition")),
                  Map.of("status", nextStatus)))
          .andExpect(status().isOk());
    }

    // Closing without lessonsLearned fails with 400
    mvc.perform(
            jsonBody(
                as("vikram.sec", post("/api/security/incidents/" + secIncId + "/transition")),
                Map.of("status", "CLOSED")))
        .andExpect(status().isBadRequest());

    // Closing with lessonsLearned succeeds
    MvcResult closedRes =
        mvc.perform(
                jsonBody(
                    as("vikram.sec", post("/api/security/incidents/" + secIncId + "/transition")),
                    Map.of(
                        "status", "CLOSED",
                        "lessonsLearned", "Enforced FIDO2 hardware keys for Finance payroll staff.")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(
            json
                .readTree(closedRes.getResponse().getContentAsString())
                .get("incident")
                .get("status")
                .asText())
        .isEqualTo("CLOSED");

    // 2. Run VULN_WAVE simulation scenario
    mvc.perform(jsonBody(as("admin", put("/api/simulation/mode")), Map.of("enabled", true)))
        .andExpect(status().isOk());
    mvc.perform(as("admin", post("/api/simulation/scenarios/VULN_WAVE/start")))
        .andExpect(status().isOk());

    // Simulated correlation groups appear when includeSimulated=true
    MvcResult groupsRes =
        mvc.perform(
                as("vikram.sec", get("/api/security/correlation-groups?includeSimulated=true")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode groups = json.readTree(groupsRes.getResponse().getContentAsString());
    assertThat(groups.size()).isPositive();
    long groupId = groups.get(0).get("id").asLong();

    // Raise security incident from correlation group
    MvcResult raiseRes =
        mvc.perform(
                as(
                    "vikram.sec",
                    post("/api/security/correlation-groups/" + groupId + "/raise-incident")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(
            json
                .readTree(raiseRes.getResponse().getContentAsString())
                .get("incident")
                .get("id")
                .asLong())
        .isPositive();

    // Purge simulation data
    mvc.perform(as("admin", delete("/api/simulation/data"))).andExpect(status().isOk());
  }
}

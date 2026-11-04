package com.nexus360.dex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class DexIT extends IntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void cleanupSim() throws Exception {
    mvc.perform(jsonBody(as("admin", put("/api/simulation/mode")), Map.of("enabled", false)))
        .andExpect(status().isOk());
  }

  @Test
  void rbacExperienceScoreCalculationEmployeeMePortalAndProactiveIncidentRule() throws Exception {
    // 1. RBAC: EMPLOYEE can access /api/dex/me, but is denied /api/dex/overview and writes
    mvc.perform(as("rohan.kulkarni", get("/api/dex/me")))
        .andExpect(status().isOk());
    mvc.perform(as("rohan.kulkarni", get("/api/dex/overview")))
        .andExpect(status().isForbidden());
    mvc.perform(
            jsonBody(
                as("rohan.kulkarni", post("/api/dex/proactive-rules")),
                Map.of("name", "Forbidden Rule", "thresholdScore", 60.0)))
        .andExpect(status().isForbidden());

    // AUDITOR can read /api/dex/overview, denied writes
    mvc.perform(as("ananya.bose", get("/api/dex/overview")))
        .andExpect(status().isOk());
    mvc.perform(
            jsonBody(
                as("ananya.bose", post("/api/dex/proactive-rules")),
                Map.of("name", "Auditor Rule", "thresholdScore", 60.0)))
        .andExpect(status().isForbidden());

    // 2. Verify INSUFFICIENT_DATA when no endpoints exist, while serviceExperience is computed
    jdbc.update("DELETE FROM dex_endpoints WHERE NOT simulated");
    MvcResult initOverviewRes =
        mvc.perform(as("riya.varma", get("/api/dex/overview")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode initOverview = json.readTree(initOverviewRes.getResponse().getContentAsString());
    assertThat(initOverview.get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(initOverview.get("serviceExperience").size()).isPositive();

    // Employee /api/dex/me returns NO_DEVICE when no endpoint is registered
    MvcResult noDevRes =
        mvc.perform(as("rohan.kulkarni", get("/api/dex/me")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(noDevRes.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo("NO_DEVICE");

    // 3. Create proactive rule for Engineering department (threshold 75.0)
    MvcResult ruleRes =
        mvc.perform(
                jsonBody(
                    as("admin", post("/api/dex/proactive-rules")),
                    Map.of(
                        "name", "Engineering Laptop Degradation Rule",
                        "departmentName", "Engineering",
                        "thresholdScore", 75.0,
                        "enabled", true)))
            .andExpect(status().isCreated())
            .andReturn();
    long ruleId = json.readTree(ruleRes.getResponse().getContentAsString()).get("id").asLong();

    // 4. Ingest app crash event and login lockout event for rohan.kulkarni
    mvc.perform(
            jsonBody(
                as("admin", post("/api/dex/ingest/app-events")),
                Map.of(
                    "appName", "NexusVPN",
                    "appVersion", "4.1.0",
                    "deviceId", "LAPTOP-ROHAN-01",
                    "userEmail", "rohan.kulkarni@nexus360.local",
                    "departmentName", "Engineering",
                    "launchTimeMs", 3200.0,
                    "pageLoadMs", 4400.0,
                    "crashed", true,
                    "hang", false,
                    "errorCode", "ERR_VPN_CRASH")))
        .andExpect(status().isOk());

    mvc.perform(
            jsonBody(
                as("admin", post("/api/dex/ingest/login-events")),
                Map.of(
                    "userEmail", "rohan.kulkarni@nexus360.local",
                    "departmentName", "Engineering",
                    "provider", "SA_AUTH",
                    "outcome", "LOCKOUT")))
        .andExpect(status().isOk());

    // 5. Ingest degraded endpoint for rohan.kulkarni in Engineering -> triggers proactive incident!
    MvcResult epRes =
        mvc.perform(
                jsonBody(
                    as("admin", post("/api/dex/ingest/endpoints")),
                    new DexDtos.IngestEndpointRequest(
                        "LAPTOP-ROHAN-01",
                        null,
                        "rohan.kulkarni@nexus360.local",
                        "Rohan Kulkarni",
                        null,
                        "Engineering",
                        "Windows 11 Enterprise",
                        "23H2",
                        "ThinkPad X1 Carbon",
                        94.0,
                        92.0,
                        88.0,
                        85.0,
                        7,
                        64.0,
                        false)))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode ep = json.readTree(epRes.getResponse().getContentAsString());
    assertThat(ep.get("deviceHealthScore").asDouble()).isLessThan(50.0);
    assertThat(ep.get("experienceScore").asDouble()).isLessThan(60.0);
    assertThat(ep.get("scoreBreakdown").has("deviceHealth")).isTrue();
    assertThat(ep.get("scoreBreakdown").has("appPerformance")).isTrue();
    assertThat(ep.get("scoreBreakdown").has("loginSuccess")).isTrue();
    assertThat(ep.get("scoreBreakdown").has("ticketSignals")).isTrue();

    // 6. Employee /api/dex/me now returns OK with their device summary
    MvcResult myDevRes =
        mvc.perform(as("rohan.kulkarni", get("/api/dex/me")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode myDev = json.readTree(myDevRes.getResponse().getContentAsString());
    assertThat(myDev.get("status").asText()).isEqualTo("OK");
    assertThat(myDev.get("device").get("deviceId").asText()).isEqualTo("LAPTOP-ROHAN-01");

    // 7. Verify proactive incident was created and linked to the rule
    Long triggeredIncId =
        jdbc.queryForObject(
            "SELECT last_triggered_incident_id FROM dex_proactive_rules WHERE id = ?",
            Long.class,
            ruleId);
    assertThat(triggeredIncId).isNotNull().isPositive();

    Long notifCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE type = 'DEX_PROACTIVE_INCIDENT'",
            Long.class);
    assertThat(notifCount).isPositive();
  }

  @Test
  void endpointDegradationSimulationScenarioAndPurge() throws Exception {
    mvc.perform(jsonBody(as("admin", put("/api/simulation/mode")), Map.of("enabled", true)))
        .andExpect(status().isOk());
    mvc.perform(as("admin", post("/api/simulation/scenarios/ENDPOINT_DEGRADATION/start")))
        .andExpect(status().isOk());

    MvcResult simOverviewRes =
        mvc.perform(as("riya.varma", get("/api/dex/overview?includeSimulated=true")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode simOverview = json.readTree(simOverviewRes.getResponse().getContentAsString());
    assertThat(simOverview.get("status").asText()).isEqualTo("OK");
    assertThat(simOverview.get("simulated").asBoolean()).isTrue();
    assertThat(simOverview.get("endpointCount").asLong()).isGreaterThanOrEqualTo(3L);

    mvc.perform(as("admin", delete("/api/simulation/data"))).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM dex_endpoints WHERE simulated = TRUE", Long.class))
        .isZero();
  }
}

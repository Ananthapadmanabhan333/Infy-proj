package com.nexus360.finops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class FinOpsIT extends IntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void cleanupSim() throws Exception {
    mvc.perform(jsonBody(as("admin", put("/api/simulation/mode")), Map.of("enabled", false)))
        .andExpect(status().isOk());
  }

  @Test
  void rbacInsufficientDataDryRunValidationIngestAllocationBudgetsAnomaliesAndRecommendations()
      throws Exception {
    // 1. RBAC checks
    mvc.perform(as("rohan.kulkarni", get("/api/finops/overview")))
        .andExpect(status().isForbidden());
    mvc.perform(as("ananya.bose", get("/api/finops/overview")))
        .andExpect(status().isOk());
    mvc.perform(
            jsonBody(
                as("ananya.bose", post("/api/finops/sources")),
                Map.of("name", "Forbidden Source", "kind", "FOCUS_CSV", "providerName", "AWS")))
        .andExpect(status().isForbidden());

    // 2. Clean any prior non-simulated cost items and verify INSUFFICIENT_DATA
    jdbc.update("DELETE FROM finops_cost_items WHERE NOT simulated");
    MvcResult initOverview =
        mvc.perform(as("neha.finops", get("/api/finops/overview")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(initOverview.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo("INSUFFICIENT_DATA");

    // 3. Dry-run with row validation errors (bad date + negative cost) -> not persisted
    String badCsv =
        "ChargePeriodStart,ProviderName,ServiceName,ResourceId,BilledCost,EffectiveCost\n"
            + "not-a-date,AWS,AmazonRDS,payroll-db-primary,100.00,100.00\n"
            + "2026-11-01,AWS,AmazonRDS,payroll-db-primary,-50.00,-50.00\n";

    MvcResult badIngestRes =
        mvc.perform(
                jsonBody(
                    as("neha.finops", post("/api/finops/ingest")),
                    Map.of("format", "FOCUS_CSV", "dryRun", true, "csvContent", badCsv)))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode badIngest = json.readTree(badIngestRes.getResponse().getContentAsString());
    assertThat(badIngest.get("errorRows").asInt()).isGreaterThanOrEqualTo(2);
    assertThat(badIngest.get("appliedCount").asInt()).isZero();

    // Still INSUFFICIENT_DATA after failed/dryRun ingest
    assertThat(
            json
                .readTree(
                    mvc.perform(as("neha.finops", get("/api/finops/overview")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("status")
                .asText())
        .isEqualTo("INSUFFICIENT_DATA");

    // 4. Create a budget with low amount ($400) for current month so ingestion crosses 50%, 80%, 100%
    String ym = YearMonth.now().toString();
    MvcResult budgetRes =
        mvc.perform(
                jsonBody(
                    as("neha.finops", post("/api/finops/budgets")),
                    Map.of(
                        "name", "Payroll RDS Monthly Guardrail",
                        "targetType", "COST_CENTER",
                        "targetKey", "CC-FIN-100",
                        "periodMonth", ym,
                        "amount", 400.00,
                        "thresholds", List.of(50, 80, 100))))
            .andExpect(status().isCreated())
            .andReturn();
    long budgetId = json.readTree(budgetRes.getResponse().getContentAsString()).get("id").asLong();

    // 5. Clean FOCUS CSV apply (4 days on payroll-db-primary with spike on day 4 + idle instance)
    String validCsv =
        "ChargePeriodStart,ProviderName,SubAccountId,ServiceName,ResourceId,ResourceName,BilledCost,EffectiveCost,Tags,CpuUtilAvg,MemUtilAvg\n"
            + ym
            + "-01,AWS,acc-01,AmazonRDS,payroll-db-primary,Payroll Primary DB,90.00,90.00,\"service=PAYROLL;costCenter=CC-FIN-100\",45.0,60.0\n"
            + ym
            + "-02,AWS,acc-01,AmazonRDS,payroll-db-primary,Payroll Primary DB,92.00,92.00,\"service=PAYROLL;costCenter=CC-FIN-100\",46.0,62.0\n"
            + ym
            + "-03,AWS,acc-01,AmazonRDS,payroll-db-primary,Payroll Primary DB,91.00,91.00,\"service=PAYROLL;costCenter=CC-FIN-100\",44.0,59.0\n"
            + ym
            + "-04,AWS,acc-01,AmazonRDS,payroll-db-primary,Payroll Primary DB,360.00,360.00,\"service=PAYROLL;costCenter=CC-FIN-100\",85.0,88.0\n"
            + ym
            + "-04,AWS,acc-01,AmazonEC2,erp-batch-idle-01,ERP Idle Batch Node,55.00,55.00,\"service=ERP;costCenter=CC-ENG-200\",1.8,12.0\n";

    MvcResult applyRes =
        mvc.perform(
                jsonBody(
                    as("neha.finops", post("/api/finops/ingest")),
                    Map.of("format", "FOCUS_CSV", "dryRun", false, "csvContent", validCsv)))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode applyNode = json.readTree(applyRes.getResponse().getContentAsString());
    assertThat(applyNode.get("errorRows").asInt()).isZero();
    assertThat(applyNode.get("appliedCount").asInt()).isEqualTo(5);
    assertThat(applyNode.get("totalEffectiveCost").asDouble()).isEqualTo(688.00);

    // Re-ingesting identical CSV is idempotent (totalEffectiveCost in overview stays 688.00)
    mvc.perform(
            jsonBody(
                as("neha.finops", post("/api/finops/ingest")),
                Map.of("format", "FOCUS_CSV", "dryRun", false, "csvContent", validCsv)))
        .andExpect(status().isOk());

    MvcResult overviewAfterRes =
        mvc.perform(as("neha.finops", get("/api/finops/overview")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode overviewAfter = json.readTree(overviewAfterRes.getResponse().getContentAsString());
    assertThat(overviewAfter.get("status").asText()).isEqualTo("OK");
    assertThat(overviewAfter.get("totalEffectiveCost").asDouble()).isEqualTo(688.00);
    assertThat(overviewAfter.get("byCostCenter").size()).isGreaterThanOrEqualTo(2);

    // 6. Verify budget status, linear forecast, and FINOPS_BUDGET_THRESHOLD notification
    MvcResult budgetsRes =
        mvc.perform(as("neha.finops", get("/api/finops/budgets")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode budgets = json.readTree(budgetsRes.getResponse().getContentAsString());
    JsonNode ourBudget = null;
    for (JsonNode b : budgets) {
      if (b.get("id").asLong() == budgetId) {
        ourBudget = b;
        break;
      }
    }
    assertThat(ourBudget).isNotNull();
    assertThat(ourBudget.get("spentToDate").asDouble()).isEqualTo(633.00);
    assertThat(ourBudget.get("utilizationPercent").asDouble()).isGreaterThan(100.0);
    assertThat(ourBudget.get("forecastedEndOfMonth").asDouble()).isGreaterThan(633.00);
    assertThat(ourBudget.get("status").asText()).isEqualTo("EXCEEDED");

    Long notifCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE type = 'FINOPS_BUDGET_THRESHOLD'",
            Long.class);
    assertThat(notifCount).isPositive();

    // 7. Verify cost anomaly detected on payroll-db-primary
    MvcResult anomaliesRes =
        mvc.perform(as("neha.finops", get("/api/finops/anomalies")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode anomalies = json.readTree(anomaliesRes.getResponse().getContentAsString());
    assertThat(anomalies.size()).isPositive();
    assertThat(anomalies.get(0).get("scopeKey").asText()).isEqualTo("payroll-db-primary");

    // 8. Verify recommendation generated and accept it -> creates DRAFT ChangeRequest
    MvcResult recsRes =
        mvc.perform(as("neha.finops", get("/api/finops/recommendations")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode recs = json.readTree(recsRes.getResponse().getContentAsString());
    assertThat(recs.size()).isPositive();
    long recId = recs.get(0).get("id").asLong();

    MvcResult acceptRes =
        mvc.perform(as("neha.finops", post("/api/finops/recommendations/" + recId + "/accept")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode acceptedRec = json.readTree(acceptRes.getResponse().getContentAsString());
    assertThat(acceptedRec.get("status").asText()).isEqualTo("ACCEPTED");
    long changeId = acceptedRec.get("changeRequestId").asLong();
    assertThat(changeId).isPositive();
    assertThat(
            jdbc.queryForObject("SELECT status FROM changes WHERE id = ?", String.class, changeId))
        .isEqualTo("DRAFT");
  }

  @Test
  void costSpikeSimulationScenarioAndPurge() throws Exception {
    mvc.perform(jsonBody(as("admin", put("/api/simulation/mode")), Map.of("enabled", true)))
        .andExpect(status().isOk());
    mvc.perform(as("admin", post("/api/simulation/scenarios/COST_SPIKE/start")))
        .andExpect(status().isOk());

    MvcResult simOverviewRes =
        mvc.perform(as("neha.finops", get("/api/finops/overview?includeSimulated=true")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode simOverview = json.readTree(simOverviewRes.getResponse().getContentAsString());
    assertThat(simOverview.get("status").asText()).isEqualTo("OK");
    assertThat(simOverview.get("simulated").asBoolean()).isTrue();

    mvc.perform(as("admin", delete("/api/simulation/data"))).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM finops_cost_items WHERE simulated = TRUE", Long.class))
        .isZero();
  }
}

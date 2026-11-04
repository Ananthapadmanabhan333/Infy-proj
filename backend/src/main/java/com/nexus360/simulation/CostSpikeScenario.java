package com.nexus360.simulation;

import com.nexus360.finops.FinOpsDtos.CostIngestRequest;
import com.nexus360.finops.FinOpsDtos.CostIngestResultDto;
import com.nexus360.finops.FinOpsService;
import com.nexus360.security.Actor;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * COST_SPIKE: ingests multi-day simulated FOCUS CSV rows with a cost spike on payroll-db-primary
 * and an idle analytics cluster, triggering anomaly detection and savings recommendations.
 */
@Component
public class CostSpikeScenario implements SimulationScenario {

  private final FinOpsService finOpsService;

  public CostSpikeScenario(FinOpsService finOpsService) {
    this.finOpsService = finOpsService;
  }

  @Override
  public String code() {
    return "COST_SPIKE";
  }

  @Override
  public String title() {
    return "Cloud cost spike & idle resource waste";
  }

  @Override
  public String description() {
    return "Ingests multi-day FOCUS cost items with a sudden spike on payroll-db-primary and an idle analytics node.";
  }

  @Override
  public String module() {
    return "FINOPS";
  }

  @Override
  public Set<String> ownedServiceNames() {
    return Set.of("payroll-db");
  }

  @Override
  public void start(SimulationContext ctx) {
    Actor actor = ctx.actor() != null ? ctx.actor() : Actor.SYSTEM;

    LocalDate today = LocalDate.now();
    StringBuilder csv = new StringBuilder();
    csv.append(
        "ChargePeriodStart,ProviderName,SubAccountId,ServiceName,ResourceId,ResourceName,RegionId,BilledCost,EffectiveCost,BillingCurrency,Tags,CpuUtilAvg,MemUtilAvg\n");

    double[] dbCosts = {115.0, 118.0, 122.0, 119.0, 485.0};
    for (int i = 0; i < dbCosts.length; i++) {
      LocalDate d = today.minusDays(dbCosts.length - 1 - i);
      double c = dbCosts[i];
      csv.append(d)
          .append(",AWS,acc-prod-01,AmazonRDS,payroll-db-primary,Payroll Primary RDS,ap-south-1,")
          .append(c)
          .append(",")
          .append(c)
          .append(",USD,\"service=PAYROLL;costCenter=CC-FIN-100\",42.0,61.0\n");
    }

    for (int i = 0; i < 4; i++) {
      LocalDate d = today.minusDays(3 - i);
      csv.append(d)
          .append(
              ",AWS,acc-stg-02,AmazonEC2,analytics-staging-idle-01,Staging Idle Worker,ap-south-1,48.00,48.00,USD,\"service=ERP;costCenter=CC-ENG-200\",2.1,14.0\n");
    }

    CostIngestResultDto res =
        finOpsService.ingest(
            new CostIngestRequest(null, "FOCUS_CSV", false, csv.toString(), Map.of(), true),
            actor);

    ctx.count("appliedCostRows", res.appliedCount());
  }

  @Override
  public void tick(SimulationContext ctx) {
    ctx.state().put("lastTick", ctx.tick());
  }
}

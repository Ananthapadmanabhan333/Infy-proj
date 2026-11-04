package com.nexus360.simulation;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.common.ApiException;
import com.nexus360.deployment.Deployment;
import com.nexus360.deployment.DeploymentDtos.DeploymentDto;
import com.nexus360.deployment.DeploymentDtos.DeploymentRequest;
import com.nexus360.simulation.ScenarioTelemetry.Batch;
import com.nexus360.simulation.ScenarioTelemetry.Conditions;
import com.nexus360.simulation.ScenarioTelemetry.Profile;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * DEPLOY_REGRESSION: a simulated deployment of HR Portal 5.3.0 (recorded through the deployment service, flagged simulated) followed
 * by a latency step: from the first tick the HR portal reports {@code service.version 5.3.0} (a tracked resource attribute change in
 * the twin change timeline) and its request latency is 1.8× the baseline with higher CPU. Starting back-fills 90 normal minutes on
 * version 5.2.0. The fault window starts at the deployment.
 */
@Component
public class DeployRegressionScenario implements SimulationScenario {

    static final Duration BACKFILL = Duration.ofMinutes(90);
    static final String SERVICE_CODE = "HR-PORTAL";
    static final Profile BEFORE = new Profile("hr-portal", "hr-portal-web-01", "/portal/home", "5.2.0", null);
    static final Profile AFTER = new Profile("hr-portal", "hr-portal-web-01", "/portal/home", "5.3.0", null);
    static final Conditions NORMAL = new Conditions(60, 90, 0.002, 0.25);
    static final Conditions REGRESSED = new Conditions(60, 162, 0.004, 0.45);

    @Override
    public String code() {
        return "DEPLOY_REGRESSION";
    }

    @Override
    public String title() {
        return "Deployment regression";
    }

    @Override
    public String description() {
        return "Simulated HR Portal 5.3.0 deployment followed by a latency step (1.8×): change proximity and version change in the twin timeline.";
    }

    @Override
    public Set<String> ownedServiceNames() {
        return Set.of("hr-portal");
    }

    @Override
    public void start(SimulationContext ctx) {
        BusinessService service = ctx.service(SERVICE_CODE).orElseThrow(() -> ApiException.conflict(
                "DEPLOY_REGRESSION needs the business service " + SERVICE_CODE + " (demo organisation)"));
        Batch batch = new Batch();
        Random r = ctx.random();
        Instant end = ctx.now().truncatedTo(ChronoUnit.MINUTES);
        for (Instant t = end.minus(BACKFILL); t.isBefore(end); t = t.plusSeconds(60)) {
            ScenarioTelemetry.emitService(batch, r, BEFORE, NORMAL, t, t.getEpochSecond() / 60 % 5 == 0, false);
        }
        batch.flush(ctx);
        DeploymentDto d = ctx.recordDeployment(new DeploymentRequest(service.getId(), Deployment.Environment.PRODUCTION, "5.3.0", null, null,
                Deployment.Status.SUCCEEDED, null, null, "Simulated deployment (scenario DEPLOY_REGRESSION, run " + ctx.runId() + ")", ctx.now()));
        ctx.state().put("deploymentId", d.id());
        ctx.state().put("deploymentNumber", d.number());
        long window = ctx.openFaultWindow("Latency regression after simulated deployment " + d.number(), service.getId(), ctx.ciId("HR Portal Application"),
                "http.server.request.duration", end);
        ctx.state().put("faultWindowId", window);
    }

    @Override
    public void tick(SimulationContext ctx) {
        Batch batch = new Batch();
        Instant t = ctx.now().truncatedTo(ChronoUnit.MINUTES);
        boolean regressed = ctx.tick() >= 1;
        ScenarioTelemetry.emitService(batch, ctx.random(), regressed ? AFTER : BEFORE, regressed ? REGRESSED : NORMAL, t, true, true);
        batch.flush(ctx);
    }
}

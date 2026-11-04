package com.nexus360.simulation;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.simulation.ScenarioTelemetry.Batch;
import com.nexus360.simulation.ScenarioTelemetry.Conditions;
import com.nexus360.simulation.ScenarioTelemetry.Pool;
import com.nexus360.simulation.ScenarioTelemetry.Profile;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * PAYROLL_DB_SATURATION: the payroll database connection pool fills up. Starting back-fills 90 normal minutes; from the second tick the
 * pool's used connections climb by about six per minute to the maximum (50), pending requests queue up, payroll API latency and error
 * ratio rise, the database logs FATAL "remaining connection slots" and the API logs Hikari timeouts, and spans fail. With the demo
 * anomaly detectors this produces anomalies → alerts (source OBSERVABILITY) → a correlation group → an incident. The fault window
 * (from the second tick until stop) is recorded for detector evaluation.
 */
@Component
public class PayrollDbSaturationScenario implements SimulationScenario {

    static final Duration BACKFILL = Duration.ofMinutes(90);
    static final Profile API = new Profile("payroll-api", "payroll-app-01", "/api/payslips", "2.4.1", "payroll-db");
    static final Conditions NORMAL = new Conditions(120, 120, 0.004, 0.35);
    static final Pool NORMAL_POOL = new Pool("payroll-db", "payroll-db-primary", 12, 50, 0, 8);
    static final int FAULT_FROM_TICK = 2;

    @Override
    public String code() {
        return "PAYROLL_DB_SATURATION";
    }

    @Override
    public String title() {
        return "Payroll database saturation";
    }

    @Override
    public String description() {
        return "Payroll DB connection pool and API latency rise, error logs and failing spans: anomalies, correlated alerts and an incident.";
    }

    @Override
    public Set<String> ownedServiceNames() {
        return Set.of("payroll-api", "payroll-db");
    }

    @Override
    public void start(SimulationContext ctx) {
        Batch batch = new Batch();
        Random r = ctx.random();
        Instant end = ctx.now().truncatedTo(ChronoUnit.MINUTES);
        for (Instant t = end.minus(BACKFILL); t.isBefore(end); t = t.plusSeconds(60)) {
            ScenarioTelemetry.emitService(batch, r, API, NORMAL, t, t.getEpochSecond() / 60 % 5 == 0, false);
            ScenarioTelemetry.emitPool(batch, r, NORMAL_POOL, t);
        }
        batch.flush(ctx);
        ctx.state().put("backfilledMinutes", BACKFILL.toMinutes());
    }

    @Override
    public void tick(SimulationContext ctx) {
        int k = ctx.tick();
        Instant t = ctx.now().truncatedTo(ChronoUnit.MINUTES);
        Batch batch = new Batch();
        if (k < FAULT_FROM_TICK) {
            ScenarioTelemetry.emitService(batch, ctx.random(), API, NORMAL, t, true, true);
            ScenarioTelemetry.emitPool(batch, ctx.random(), NORMAL_POOL, t);
        } else {
            int step = k - FAULT_FROM_TICK + 1;
            double used = Math.min(50, 12 + 6.0 * step);
            double pending = Math.max(0, (12 + 6.0 * step) - 50) * 2;
            double latency = Math.min(4000, 120 * (1 + 0.6 * step));
            double errorRatio = Math.min(0.6, 0.05 * step);
            ScenarioTelemetry.emitService(batch, ctx.random(), API, new Conditions(120, latency, errorRatio, Math.min(0.95, 0.35 + 0.05 * step)), t, true,
                    true);
            ScenarioTelemetry.emitPool(batch, ctx.random(), new Pool("payroll-db", "payroll-db-primary", used, 50, pending, 8 * (1 + step)), t);
            if (!ctx.state().containsKey("faultWindowId")) {
                Long serviceId = ctx.service("PAYROLL").map(BusinessService::getId).orElse(null);
                long id = ctx.openFaultWindow("Payroll DB connection pool saturation", serviceId, ctx.ciId("payroll-db-primary"),
                        "db.client.connections.usage", t);
                ctx.state().put("faultWindowId", id);
            }
        }
        batch.flush(ctx);
    }
}

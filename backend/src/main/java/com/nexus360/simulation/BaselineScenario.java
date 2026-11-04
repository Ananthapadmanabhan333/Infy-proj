package com.nexus360.simulation;

import com.nexus360.simulation.ScenarioTelemetry.Batch;
import com.nexus360.simulation.ScenarioTelemetry.Conditions;
import com.nexus360.simulation.ScenarioTelemetry.Pool;
import com.nexus360.simulation.ScenarioTelemetry.Profile;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * BASELINE: steady metrics, logs and traces for the demo services (payroll API and its database pool, HR portal, ERP finance) —
 * request-duration histograms, CPU, connection pool, access logs and two traces per service and minute. Starting back-fills the last
 * 60 minutes (metrics and logs) so anomaly detectors have a baseline. Services owned by another running scenario are skipped.
 */
@Component
public class BaselineScenario implements SimulationScenario {

    static final Duration BACKFILL = Duration.ofMinutes(60);

    record Svc(Profile profile, Conditions conditions) {}

    static final List<Svc> SERVICES = List.of(
            new Svc(new Profile("payroll-api", "payroll-app-01", "/api/payslips", "2.4.1", "payroll-db"), new Conditions(120, 120, 0.004, 0.35)),
            new Svc(new Profile("hr-portal", "hr-portal-web-01", "/portal/home", "5.2.0", null), new Conditions(60, 90, 0.002, 0.25)),
            new Svc(new Profile("erp-finance", "erp-app-01", "/ledger/entries", "7.2", null), new Conditions(80, 150, 0.003, 0.40)));

    static final Pool PAYROLL_POOL = new Pool("payroll-db", "payroll-db-primary", 12, 50, 0, 8);

    @Override
    public String code() {
        return "BASELINE";
    }

    @Override
    public String title() {
        return "Baseline telemetry";
    }

    @Override
    public String description() {
        return "Steady metrics, logs and traces for the demo services (payroll API and database, HR portal, ERP finance). Back-fills 60 minutes.";
    }

    @Override
    public void start(SimulationContext ctx) {
        Batch batch = new Batch();
        Instant end = ctx.now().truncatedTo(ChronoUnit.MINUTES);
        Random r = ctx.random();
        for (Instant t = end.minus(BACKFILL); t.isBefore(end); t = t.plusSeconds(60)) {
            emit(ctx, batch, r, t, t.getEpochSecond() / 60 % 5 == 0, false);
        }
        batch.flush(ctx);
        ctx.state().put("backfilledMinutes", BACKFILL.toMinutes());
    }

    @Override
    public void tick(SimulationContext ctx) {
        Batch batch = new Batch();
        emit(ctx, batch, ctx.random(), ctx.now().truncatedTo(ChronoUnit.MINUTES), true, true);
        batch.flush(ctx);
    }

    private static void emit(SimulationContext ctx, Batch batch, Random r, Instant t, boolean logs, boolean traces) {
        for (Svc s : SERVICES) {
            if (!ctx.ownedByAnotherScenario(s.profile().serviceName())) {
                ScenarioTelemetry.emitService(batch, r, s.profile(), s.conditions(), t, logs, traces);
            }
        }
        if (!ctx.ownedByAnotherScenario(PAYROLL_POOL.serviceName())) {
            ScenarioTelemetry.emitPool(batch, r, PAYROLL_POOL, t);
        }
    }
}

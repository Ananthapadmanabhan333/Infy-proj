package com.nexus360.simulation;

import java.time.Duration;
import java.util.Set;

/**
 * A simulation scenario (RELEASE4_SPEC §1). Scenarios are Spring beans; {@link SimulationService} discovers them by {@link #code()}.
 * They generate labelled sample data <b>only through the real ingestion services</b> (OTLP ingestion, the deployment service, later the
 * vulnerability / cost / endpoint ingestion of BE-7 and BE-8) with {@code simulated = true}, so the pipelines downstream (roll-ups,
 * anomaly detection, alerts, correlation, incidents) are exercised exactly as with real data. A scenario never writes real records.
 *
 * <p>Lifecycle: {@link #start} once when an administrator starts the scenario (e.g. back-fill a baseline, record a simulated
 * deployment), then {@link #tick} every {@link #tickInterval()} until it is stopped (or {@link #maxDuration()} elapses), then
 * {@link #stop}. Each call runs in its own transaction; {@link SimulationContext#state()} persists between calls.
 *
 * <p>Later phases add {@code VULN_WAVE} (BE-7), {@code COST_SPIKE} and {@code ENDPOINT_DEGRADATION} (BE-8) by implementing this interface.
 */
public interface SimulationScenario {

    /** Upper-case code, e.g. {@code PAYROLL_DB_SATURATION} (pattern {@code ^[A-Z][A-Z0-9_]{1,29}$}). */
    String code();

    String title();

    String description();

    /** The module the scenario feeds (OBSERVABILITY, SECOPS, FINOPS, DEX), for the settings UI. */
    default String module() {
        return "OBSERVABILITY";
    }

    default Duration tickInterval() {
        return Duration.ofMinutes(1);
    }

    /** {@code null} = runs until stopped; otherwise the run COMPLETES after this long. */
    default Duration maxDuration() {
        return null;
    }

    /** OTLP {@code service.name}s whose telemetry this scenario generates exclusively while running (BASELINE skips them). */
    default Set<String> ownedServiceNames() {
        return Set.of();
    }

    void start(SimulationContext ctx);

    void tick(SimulationContext ctx);

    default void stop(SimulationContext ctx) {
    }
}

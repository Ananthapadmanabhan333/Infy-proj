package com.nexus360.simulation;

import com.nexus360.admin.SettingsService;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.deployment.DeploymentService;
import com.nexus360.observability.OtlpIngestService;
import com.nexus360.observability.TelemetryJson;
import com.nexus360.security.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Simulation mode (RELEASE4_SPEC §1). The setting {@code simulation.enabled} (SYSTEM_ADMIN, audited) is effective only where the
 * deployment allows it ({@code app.simulation.allowed} / SIMULATION_ALLOWED, false by default — production stays off unless set).
 * Scenarios ({@link SimulationScenario} beans) are started and stopped by simulation administrators; the scheduler (advisory lock
 * 360010) ticks running scenarios; switching the mode off stops them. {@link #purge} deletes simulated rows only.
 */
@Slf4j
@Service
public class SimulationService {

    public static final String SETTING = "simulation.enabled";
    static final long LOCK_KEY = 360_010L;

    private final Map<String, SimulationScenario> scenarios = new TreeMap<>();
    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final AuditService audit;
    private final OtlpIngestService otlp;
    private final DeploymentService deployments;
    private final BusinessServiceRepository services;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;
    private final boolean allowed;
    private final boolean schedulerEnabled;

    public SimulationService(List<SimulationScenario> beans, JdbcTemplate jdbc, SettingsService settings, AuditService audit, OtlpIngestService otlp,
                             DeploymentService deployments, BusinessServiceRepository services, Clock clock, PlatformTransactionManager transactions,
                             @Value("${app.simulation.allowed:false}") boolean allowed,
                             @Value("${app.simulation.scheduler-enabled:true}") boolean schedulerEnabled) {
        for (SimulationScenario s : beans) {
            if (!s.code().matches("^[A-Z][A-Z0-9_]{1,29}$")) {
                throw new IllegalStateException("Invalid simulation scenario code " + s.code());
            }
            if (scenarios.put(s.code(), s) != null) {
                throw new IllegalStateException("Duplicate simulation scenario " + s.code());
            }
        }
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
        this.otlp = otlp;
        this.deployments = deployments;
        this.services = services;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.allowed = allowed;
        this.schedulerEnabled = schedulerEnabled;
    }

    // ------------------------------------------------------------------ DTOs

    public record ScenarioDto(String code, String title, String description, String module, long tickIntervalSeconds, boolean running, Long runId,
                              Instant startedAt, Instant lastTickAt, int tickCount) {}

    public record RunDto(Long id, String scenario, String status, Instant startedAt, Instant stoppedAt, Instant lastTickAt, Instant nextTickAt, int tickCount,
                         Map<String, Object> summary, String error, String startedByName, String stoppedByName) {}

    /** {@code enabled}: the setting is on and the deployment allows simulation; {@code simulatedRecords}: simulated rows currently stored. */
    public record StatusDto(boolean enabled, boolean allowed, boolean settingEnabled, List<ScenarioDto> scenarios, List<RunDto> runningRuns,
                            Map<String, Long> simulatedRecords) {}

    // ------------------------------------------------------------------ status

    public boolean enabled() {
        return allowed && settings.getBoolean(SETTING, false);
    }

    public StatusDto status() {
        List<RunDto> running = jdbc.query(RUN_SELECT + " where status = 'RUNNING' order by started_at", SimulationService::run);
        Map<String, RunDto> byScenario = new LinkedHashMap<>();
        running.forEach(r -> byScenario.put(r.scenario(), r));
        List<ScenarioDto> list = scenarios.values().stream().map(s -> {
            RunDto r = byScenario.get(s.code());
            return new ScenarioDto(s.code(), s.title(), s.description(), s.module(), s.tickInterval().toSeconds(), r != null, r == null ? null : r.id(),
                    r == null ? null : r.startedAt(), r == null ? null : r.lastTickAt(), r == null ? 0 : r.tickCount());
        }).toList();
        return new StatusDto(enabled(), allowed, settings.getBoolean(SETTING, false), list, running, simulatedCounts());
    }

    public List<RunDto> runs(Integer limit) {
        int max = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return jdbc.query(RUN_SELECT + " order by started_at desc, id desc limit ?", SimulationService::run, max);
    }

    Map<String, Long> simulatedCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String table : PURGE_TABLES) {
            out.put(table, jdbc.queryForObject("select count(*) from " + table + " where simulated", Long.class));
        }
        return out;
    }

    // ------------------------------------------------------------------ mode

    public StatusDto setMode(boolean enabled, Actor actor) {
        if (enabled && !allowed) {
            throw ApiException.conflict("Simulation mode is not allowed on this deployment (set SIMULATION_ALLOWED=true to permit it)");
        }
        boolean before = settings.getBoolean(SETTING, false);
        tx.executeWithoutResult(s -> settings.update(SETTING, String.valueOf(enabled)));
        audit.record(enabled ? "SIMULATION_MODE_ENABLED" : "SIMULATION_MODE_DISABLED", "SIMULATION", null, Map.of("from", before, "to", enabled));
        if (!enabled) {
            stopAll(actor, "Simulation mode switched off");
        }
        return status();
    }

    // ------------------------------------------------------------------ scenarios

    public RunDto start(String code, Actor actor) {
        SimulationScenario scenario = scenario(code);
        if (!enabled()) {
            throw ApiException.conflict(allowed ? "Simulation mode is off: switch it on first" : "Simulation mode is not allowed on this deployment");
        }
        Instant now = clock.instant();
        Long runId;
        try {
            runId = tx.execute(s -> jdbc.queryForObject("insert into simulation_runs (scenario, status, started_at, next_tick_at, started_by_id, "
                    + "started_by_name, created_at, updated_at) values (?, 'RUNNING', ?, ?, ?, ?, ?, ?) returning id", Long.class, scenario.code(),
                    Timestamp.from(now), Timestamp.from(now.plus(scenario.tickInterval())), actor.id(), actor.name(), Timestamp.from(now), Timestamp.from(now)));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict(scenario.code() + " is already running");
        }
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        try {
            newTx.executeWithoutResult(s -> {
                SimulationContext start = context(runId, scenario, now, now, 0, actor, state, summary);
                scenario.start(start);
                scenario.tick(start);
                jdbc.update("update simulation_runs set state = ?::jsonb, summary = ?::jsonb, tick_count = 1, last_tick_at = ?, updated_at = ? where id = ?",
                        TelemetryJson.write(state), TelemetryJson.write(summary), Timestamp.from(now), Timestamp.from(now), runId);
            });
        } catch (RuntimeException e) {
            String message = e instanceof ApiException api ? api.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
            jdbc.update("update simulation_runs set status = 'FAILED', stopped_at = ?, error = ?, updated_at = ? where id = ?", Timestamp.from(now),
                    truncate(message, 1000), Timestamp.from(now), runId);
            audit.record("SIMULATION_SCENARIO_FAILED", "SIMULATION", scenario.code(), Map.of("runId", runId, "error", truncate(message, 300)));
            throw ApiException.conflict("Scenario " + scenario.code() + " could not start: " + truncate(message, 300));
        }
        audit.record("SIMULATION_SCENARIO_STARTED", "SIMULATION", scenario.code(), Map.of("runId", runId));
        return runById(runId);
    }

    public RunDto stop(String code, Actor actor) {
        SimulationScenario scenario = scenario(code);
        RunDto run = jdbc.query(RUN_SELECT + " where scenario = ? and status = 'RUNNING'", SimulationService::run, scenario.code()).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict(scenario.code() + " is not running"));
        stopRun(run, scenario, actor, "STOPPED", null);
        audit.record("SIMULATION_SCENARIO_STOPPED", "SIMULATION", scenario.code(), Map.of("runId", run.id(), "ticks", run.tickCount()));
        return runById(run.id());
    }

    private void stopRun(RunDto run, SimulationScenario scenario, Actor actor, String status, String note) {
        Instant now = clock.instant();
        newTx.executeWithoutResult(s -> {
            if (scenario != null) {
                Map<String, Object> state = new LinkedHashMap<>(state(run.id()));
                SimulationContext ctx = context(run.id(), scenario, run.startedAt(), now, run.tickCount(), actor, state, new LinkedHashMap<>(run.summary()));
                try {
                    scenario.stop(ctx);
                } catch (RuntimeException e) {
                    log.warn("Scenario {} stop hook failed: {}", scenario.code(), e.getMessage());
                }
            }
            jdbc.update("update simulation_fault_windows set ended_at = ? where run_id = ? and ended_at is null", Timestamp.from(now), run.id());
            jdbc.update("update simulation_runs set status = ?, stopped_at = ?, stopped_by_name = ?, next_tick_at = null, error = coalesce(?, error), "
                    + "updated_at = ? where id = ? and status = 'RUNNING'", status, Timestamp.from(now), actor == null ? "SA Simulation" : actor.name(), note,
                    Timestamp.from(now), run.id());
        });
    }

    private void stopAll(Actor actor, String note) {
        for (RunDto run : jdbc.query(RUN_SELECT + " where status = 'RUNNING'", SimulationService::run)) {
            stopRun(run, scenarios.get(run.scenario()), actor, "STOPPED", note);
            audit.record("SIMULATION_SCENARIO_STOPPED", "SIMULATION", run.scenario(), Map.of("runId", run.id(), "reason", note));
        }
    }

    // ------------------------------------------------------------------ ticking

    @Scheduled(fixedDelayString = "${app.simulation.tick-interval-ms:60000}", initialDelay = 30_000)
    public void scheduled() {
        if (!schedulerEnabled) {
            return;
        }
        try {
            tickDue();
        } catch (RuntimeException e) {
            log.error("Simulation scheduler failed", e);
        }
    }

    /** Ticks every running scenario whose next tick is due (advisory lock 360010); returns the number of ticks. */
    public int tickDue() {
        Integer n = tx.execute(s -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            if (!enabled()) {
                if (!jdbc.queryForList("select id from simulation_runs where status = 'RUNNING'", Long.class).isEmpty()) {
                    stopAll(null, "Simulation mode is off");
                }
                return 0;
            }
            int ticks = 0;
            for (RunDto run : jdbc.query(RUN_SELECT + " where status = 'RUNNING' and next_tick_at <= ? order by next_tick_at", SimulationService::run,
                    Timestamp.from(clock.instant()))) {
                ticks += tick(run) ? 1 : 0;
            }
            return ticks;
        });
        return n == null ? 0 : n;
    }

    /** Ticks the running run of a scenario now regardless of its schedule (tests and the scheduler). */
    public boolean tickNow(String code) {
        SimulationScenario scenario = scenario(code);
        RunDto run = jdbc.query(RUN_SELECT + " where scenario = ? and status = 'RUNNING'", SimulationService::run, scenario.code()).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict(scenario.code() + " is not running"));
        return tick(run);
    }

    private boolean tick(RunDto run) {
        SimulationScenario scenario = scenarios.get(run.scenario());
        Instant now = clock.instant();
        if (scenario == null) {
            stopRun(run, null, null, "FAILED", "Scenario " + run.scenario() + " is not available in this version");
            return false;
        }
        if (scenario.maxDuration() != null && Duration.between(run.startedAt(), now).compareTo(scenario.maxDuration()) >= 0) {
            stopRun(run, scenario, null, "COMPLETED", null);
            audit.record("SIMULATION_SCENARIO_COMPLETED", "SIMULATION", scenario.code(), Map.of("runId", run.id()));
            return false;
        }
        Map<String, Object> state = state(run.id());
        Map<String, Object> summary = new LinkedHashMap<>(run.summary());
        try {
            newTx.executeWithoutResult(s -> {
                SimulationContext ctx = context(run.id(), scenario, run.startedAt(), now, run.tickCount(), null, state, summary);
                scenario.tick(ctx);
                jdbc.update("update simulation_runs set state = ?::jsonb, summary = ?::jsonb, tick_count = tick_count + 1, last_tick_at = ?, next_tick_at = ?, "
                        + "updated_at = ? where id = ?", TelemetryJson.write(state), TelemetryJson.write(summary), Timestamp.from(now),
                        Timestamp.from(now.plus(scenario.tickInterval())), Timestamp.from(now), run.id());
            });
            return true;
        } catch (RuntimeException e) {
            log.error("Simulation scenario {} tick failed", scenario.code(), e);
            stopRun(run, scenario, null, "FAILED", truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 1000));
            audit.record("SIMULATION_SCENARIO_FAILED", "SIMULATION", scenario.code(), Map.of("runId", run.id()));
            return false;
        }
    }

    // ------------------------------------------------------------------ purge

    /** Tables with a {@code simulated} column whose simulated rows are purged (in dependency order). */
    static final List<String> PURGE_TABLES = List.of("remediation_runs", "security_correlation_groups", "security_incidents", "vulnerability_findings",
            "finops_recommendations", "finops_anomalies", "finops_cost_items", "dex_endpoints", "dex_app_events", "dex_login_events",
            "correlation_groups", "telemetry_anomalies", "alerts", "incidents", "deployments",
            "telemetry_metric_series", "telemetry_logs", "telemetry_spans", "telemetry_resource_changes", "telemetry_resources");

    /** Stops running scenarios and deletes simulated rows only (and the fault windows labelling them); audited. */
    public Map<String, Long> purge(Actor actor) {
        stopAll(actor, "Simulated data purged");
        Map<String, Long> counts = new LinkedHashMap<>();
        tx.executeWithoutResult(s -> {
            for (String table : PURGE_TABLES) {
                if (table.equals("incidents")) {
                    // Real child incidents of a simulated parent keep their data (parent_id is set null by the foreign key).
                    jdbc.update("update incidents set parent_id = null where parent_id in (select id from incidents where simulated) and not simulated");
                }
                counts.put(table, (long) jdbc.update("delete from " + table + " where simulated"));
            }
            counts.put("telemetry_rollup_queue", (long) jdbc.update("delete from telemetry_rollup_queue q where not exists (select 1 from "
                    + "telemetry_metric_points p where p.ts >= q.bucket_start and p.ts < q.bucket_start + interval '1 minute')"));
            counts.put("simulation_fault_windows", (long) jdbc.update("delete from simulation_fault_windows"));
        });
        audit.record("SIMULATION_DATA_PURGED", "SIMULATION", null, new LinkedHashMap<>(counts));
        return counts;
    }

    // ------------------------------------------------------------------ helpers

    private SimulationScenario scenario(String code) {
        SimulationScenario s = code == null ? null : scenarios.get(code.trim().toUpperCase(Locale.ROOT));
        if (s == null) {
            throw ApiException.notFound("Simulation scenario", code);
        }
        return s;
    }

    private SimulationContext context(long runId, SimulationScenario scenario, Instant startedAt, Instant now, int tick, Actor actor, Map<String, Object> state,
                                      Map<String, Object> summary) {
        Set<String> foreign = new HashSet<>();
        for (String other : jdbc.queryForList("select scenario from simulation_runs where status = 'RUNNING' and id <> ?", String.class, runId)) {
            SimulationScenario s = scenarios.get(other);
            if (s != null) {
                foreign.addAll(s.ownedServiceNames());
            }
        }
        Actor a = actor != null ? actor : new Actor(null, "simulation@nexus360", "SA Simulation", com.nexus360.user.Role.SYSTEM_ADMIN, 0);
        return new SimulationContext(runId, scenario.code(), startedAt, now, tick, a, state, summary, foreign, otlp, deployments, services, jdbc);
    }

    private Map<String, Object> state(long runId) {
        return TelemetryJson.objectMap(jdbc.queryForObject("select state::text from simulation_runs where id = ?", String.class, runId));
    }

    private RunDto runById(long id) {
        return jdbc.query(RUN_SELECT + " where id = ?", SimulationService::run, id).stream().findFirst().orElseThrow();
    }

    private static final String RUN_SELECT = "select id, scenario, status, started_at, stopped_at, last_tick_at, next_tick_at, tick_count, summary::text, error, "
            + "started_by_name, stopped_by_name from simulation_runs";

    private static RunDto run(ResultSet rs, int n) throws SQLException {
        return new RunDto(rs.getLong(1), rs.getString(2), rs.getString(3), ts(rs, 4), ts(rs, 5), ts(rs, 6), ts(rs, 7), rs.getInt(8),
                TelemetryJson.objectMap(rs.getString(9)), rs.getString(10), rs.getString(11), rs.getString(12));
    }

    private static Instant ts(ResultSet rs, int i) throws SQLException {
        Timestamp t = rs.getTimestamp(i);
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    List<String> codes() {
        return new ArrayList<>(scenarios.keySet());
    }
}

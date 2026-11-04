package com.nexus360.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.deployment.DeploymentDtos.DeploymentDto;
import com.nexus360.deployment.DeploymentDtos.DeploymentRequest;
import com.nexus360.deployment.DeploymentService;
import com.nexus360.observability.OtlpIngestService;
import com.nexus360.observability.TelemetryModel.IngestResult;
import com.nexus360.security.Actor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a scenario may use: run identity and timing, persisted {@link #state()}, a deterministic random source, and the <b>real</b>
 * ingestion services with {@code simulated = true} — OTLP ingestion ({@link #otlp}) and the deployment service
 * ({@link #recordDeployment}) — plus labelled fault windows ({@link #openFaultWindow}) used as ground truth by detector evaluation.
 */
public final class SimulationContext {

    private final long runId;
    private final String scenario;
    private final Instant startedAt;
    private final Instant now;
    private final int tick;
    private final Actor actor;
    private final Map<String, Object> state;
    private final Map<String, Object> summary;
    private final Set<String> foreignOwnedServices;
    private final OtlpIngestService otlp;
    private final DeploymentService deployments;
    private final BusinessServiceRepository services;
    private final JdbcTemplate jdbc;
    private final Random random;

    SimulationContext(long runId, String scenario, Instant startedAt, Instant now, int tick, Actor actor, Map<String, Object> state,
                      Map<String, Object> summary, Set<String> foreignOwnedServices, OtlpIngestService otlp, DeploymentService deployments,
                      BusinessServiceRepository services, JdbcTemplate jdbc) {
        this.runId = runId;
        this.scenario = scenario;
        this.startedAt = startedAt;
        this.now = now;
        this.tick = tick;
        this.actor = actor;
        this.state = state;
        this.summary = summary;
        this.foreignOwnedServices = foreignOwnedServices;
        this.otlp = otlp;
        this.deployments = deployments;
        this.services = services;
        this.jdbc = jdbc;
        this.random = new Random(runId * 1_000_003L + tick);
    }

    public long runId() {
        return runId;
    }

    public String scenario() {
        return scenario;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** The time of this call (start or tick). */
    public Instant now() {
        return now;
    }

    /** 0 for the start call's first tick, then 1, 2, ... */
    public int tick() {
        return tick;
    }

    public Actor actor() {
        return actor;
    }

    /** Scenario state persisted between calls (JSON-serialisable values only). */
    public Map<String, Object> state() {
        return state;
    }

    /** Deterministic per run and tick. */
    public Random random() {
        return random;
    }

    /** Whether another running scenario generates this service's telemetry exclusively. */
    public boolean ownedByAnotherScenario(String serviceName) {
        return foreignOwnedServices.contains(serviceName);
    }

    /** Sends an OTLP JSON export request through the real ingestion path, flagged simulated. */
    public IngestResult otlp(OtlpIngestService.Signal signal, JsonNode body) {
        IngestResult r = otlp.ingest(signal, body, null, true);
        count(switch (signal) {
            case METRICS -> "metricPoints";
            case LOGS -> "logRecords";
            case TRACES -> "spans";
        }, r.accepted());
        return r;
    }

    /** Records a deployment through the deployment service (same validation and side effects), flagged simulated. */
    public DeploymentDto recordDeployment(DeploymentRequest request) {
        DeploymentDto d = deployments.create(request, actor, true);
        count("deployments", 1);
        return d;
    }

    public Optional<BusinessService> service(String code) {
        return services.findByCodeIgnoreCase(code);
    }

    /** Opens a labelled fault window (ground truth for detector evaluation); returns its id. */
    public long openFaultWindow(String label, Long serviceId, Long ciId, String metricName, Instant from) {
        return jdbc.queryForObject("insert into simulation_fault_windows (run_id, scenario, label, service_id, ci_id, metric_name, started_at) values "
                + "(?, ?, ?, ?, ?, ?, ?) returning id", Long.class, runId, scenario, label, serviceId, ciId, metricName, Timestamp.from(from));
    }

    public Long ciId(String name) {
        return jdbc.query("select id from configuration_items where lower(name) = lower(?)", rs -> rs.next() ? rs.getLong(1) : null, name);
    }

    void count(String key, long n) {
        Object v = summary.get(key);
        long current = v instanceof Number num ? num.longValue() : 0;
        summary.put(key, current + n);
    }
}

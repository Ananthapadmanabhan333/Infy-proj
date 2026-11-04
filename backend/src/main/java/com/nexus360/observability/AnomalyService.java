package com.nexus360.observability;

import com.nexus360.alert.Alert;
import com.nexus360.alert.AlertIngestService;
import com.nexus360.alert.AlertSeverity;
import com.nexus360.alert.AlertSignal;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.cmdb.ConfigurationItem;
import com.nexus360.cmdb.ConfigurationItemRepository;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.observability.AnomalyEngine.Params;
import com.nexus360.observability.AnomalyEngine.Point;
import com.nexus360.observability.AnomalyEngine.Window;
import com.nexus360.observability.ObservabilityDtos.AnomalyDto;
import com.nexus360.observability.ObservabilityDtos.DetectorDto;
import com.nexus360.observability.ObservabilityDtos.DetectorRequest;
import com.nexus360.observability.ObservabilityDtos.EvaluateRequest;
import com.nexus360.observability.ObservabilityDtos.EvaluationResult;
import com.nexus360.observability.ObservabilityDtos.LabelDto;
import com.nexus360.observability.ObservabilityDtos.LabelWindow;
import com.nexus360.observability.ObservabilityDtos.PointDto;
import com.nexus360.observability.ObservabilityDtos.RunResult;
import com.nexus360.observability.ObservabilityDtos.WindowDto;
import com.nexus360.security.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Anomaly detectors (created by TELEMETRY_CONFIG for a metric, optionally narrowed by service, CI and series attributes), their
 * periodic evaluation ({@link #runAll}, advisory lock 360013 in {@link TelemetryJobs}), the stored anomalies and the evaluation on
 * labelled history (precision / recall). An anomaly can raise an alert (source {@code OBSERVABILITY}) through the regular alert
 * pipeline — deduplication by {@code anomaly:<detector>:<series>}, suppression, routing and, when the detector says so, conversion
 * into an incident; when the anomaly ends the alert is resolved by the source.
 */
@Slf4j
@Service
public class AnomalyService {

    static final int MAX_SERIES_PER_DETECTOR = 25;
    static final Actor OBSERVABILITY_ACTOR = new Actor(null, "observability@nexus360", "SA Observability", com.nexus360.user.Role.SYSTEM_ADMIN, 0);

    private final JdbcTemplate jdbc;
    private final TelemetryQueryService queries;
    private final TelemetryMaintenanceService maintenance;
    private final AlertIngestService alertIngest;
    private final BusinessServiceRepository services;
    private final ConfigurationItemRepository cis;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate perDetector;
    private final CorrelationService correlation;
    /** Anomalies opened without an alert in the current run (correlated after the run's transaction committed). */
    private final ThreadLocal<List<Long>> uncorrelated = ThreadLocal.withInitial(ArrayList::new);

    public AnomalyService(JdbcTemplate jdbc, TelemetryQueryService queries, TelemetryMaintenanceService maintenance, AlertIngestService alertIngest,
                          BusinessServiceRepository services, ConfigurationItemRepository cis, AuditService audit, Clock clock,
                          PlatformTransactionManager transactions, CorrelationService correlation) {
        this.correlation = correlation;
        this.jdbc = jdbc;
        this.queries = queries;
        this.maintenance = maintenance;
        this.alertIngest = alertIngest;
        this.services = services;
        this.cis = cis;
        this.audit = audit;
        this.clock = clock;
        this.perDetector = new TransactionTemplate(transactions);
        this.perDetector.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A detector row. */
    record Detector(long id, String name, String metricName, Long serviceId, Long ciId, Map<String, String> filters, String aggregation, String algorithm,
                    int window, double threshold, int consecutive, int minSamples, String direction, String seasonality, boolean includeSimulated,
                    boolean raiseAlert, AlertSeverity severity, boolean createIncident, boolean enabled) {}

    record SeriesRef(long id, Map<String, String> attributes, Long serviceId, Long ciId, String resourceServiceName, boolean simulated, Instant firstSeen) {}

    // ------------------------------------------------------------------ CRUD

    @Transactional(readOnly = true)
    public List<DetectorDto> list() {
        return jdbc.query(DETECTOR_SELECT + " order by lower(d.name)", this::detectorDto);
    }

    @Transactional(readOnly = true)
    public DetectorDto get(Long id) {
        return jdbc.query(DETECTOR_SELECT + " where d.id = ?", this::detectorDto, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Anomaly detector", id));
    }

    @Transactional
    public DetectorDto save(Long id, DetectorRequest r, Actor actor) {
        if (r.serviceId() != null && services.findById(r.serviceId()).isEmpty()) {
            throw ApiException.invalidField("serviceId", "Service does not exist");
        }
        if (r.ciId() != null && cis.findById(r.ciId()).isEmpty()) {
            throw ApiException.invalidField("ciId", "Configuration item does not exist");
        }
        if (jdbc.queryForObject("select count(*) from anomaly_detectors where lower(name) = lower(?) and id <> ?", Long.class, r.name().trim(),
                id == null ? -1L : id) > 0) {
            throw ApiException.invalidField("name", "A detector with this name already exists");
        }
        Map<String, String> filters = r.attributeFilters() == null ? Map.of() : r.attributeFilters();
        Object[] values = {r.name().trim(), r.description(), r.metricName().trim(), r.serviceId(), r.ciId(), TelemetryJson.write(filters),
                r.valueAggregation() == null ? "avg" : r.valueAggregation(), r.algorithm() == null ? "EWMA" : r.algorithm(),
                r.windowPoints() == null ? 60 : r.windowPoints(), r.threshold() == null ? 3.0 : r.threshold(),
                r.consecutivePoints() == null ? 3 : r.consecutivePoints(), r.minSamples() == null ? 30 : r.minSamples(),
                r.direction() == null ? "BOTH" : r.direction(), r.seasonality() == null ? "AUTO" : r.seasonality(), Boolean.TRUE.equals(r.includeSimulated()),
                r.raiseAlert() == null || r.raiseAlert(), (r.alertSeverity() == null ? AlertSeverity.MAJOR : r.alertSeverity()).name(),
                Boolean.TRUE.equals(r.createIncident()), r.enabled() == null || r.enabled()};
        Instant now = clock.instant();
        Long detectorId = id;
        if (id == null) {
            List<Object> args = new ArrayList<>(java.util.Arrays.asList(values));
            args.add(actor.id());
            args.add(Timestamp.from(now));
            args.add(Timestamp.from(now));
            detectorId = jdbc.queryForObject("insert into anomaly_detectors (name, description, metric_name, service_id, ci_id, attribute_filters, "
                    + "value_aggregation, algorithm, window_points, threshold, consecutive_points, min_samples, direction, seasonality, include_simulated, "
                    + "raise_alert, alert_severity, create_incident, enabled, created_by_id, created_at, updated_at) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, "
                    + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", Long.class, args.toArray());
        } else {
            get(id);
            List<Object> args = new ArrayList<>(java.util.Arrays.asList(values));
            args.add(Timestamp.from(now));
            args.add(id);
            jdbc.update("update anomaly_detectors set name = ?, description = ?, metric_name = ?, service_id = ?, ci_id = ?, attribute_filters = ?::jsonb, "
                    + "value_aggregation = ?, algorithm = ?, window_points = ?, threshold = ?, consecutive_points = ?, min_samples = ?, direction = ?, "
                    + "seasonality = ?, include_simulated = ?, raise_alert = ?, alert_severity = ?, create_incident = ?, enabled = ?, updated_at = ?, "
                    + "version = version + 1 where id = ?", args.toArray());
        }
        audit.record(id == null ? "ANOMALY_DETECTOR_CREATED" : "ANOMALY_DETECTOR_UPDATED", "ANOMALY_DETECTOR", detectorId, Map.of("name", r.name().trim(),
                "metric", r.metricName().trim(), "algorithm", values[7], "threshold", values[9], "consecutive", values[10]));
        return get(detectorId);
    }

    @Transactional
    public void delete(Long id) {
        DetectorDto d = get(id);
        jdbc.update("delete from anomaly_detectors where id = ?", id);
        audit.record("ANOMALY_DETECTOR_DELETED", "ANOMALY_DETECTOR", id, Map.of("name", d.name()));
    }

    // ------------------------------------------------------------------ anomalies

    @Transactional(readOnly = true)
    public PageResponse<AnomalyDto> anomalies(String status, Long detectorId, Long serviceId, Instant from, Instant to, boolean includeSimulated, Integer page,
                                              Integer size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where true");
        if (!includeSimulated) {
            where.append(" and not a.simulated");
        }
        if (status != null && !status.isBlank()) {
            String st = status.trim().toUpperCase(Locale.ROOT);
            if (!List.of("OPEN", "RESOLVED").contains(st)) {
                throw ApiException.invalidField("status", "status must be OPEN or RESOLVED");
            }
            where.append(" and a.status = ?");
            args.add(st);
        }
        if (detectorId != null) {
            where.append(" and a.detector_id = ?");
            args.add(detectorId);
        }
        if (serviceId != null) {
            where.append(" and a.service_id = ?");
            args.add(serviceId);
        }
        if (from != null) {
            where.append(" and coalesce(a.ended_at, a.last_point_at) >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            where.append(" and a.started_at < ?");
            args.add(Timestamp.from(to));
        }
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, PageResponse.MAX_PAGE_SIZE);
        Long total = jdbc.queryForObject("select count(*) from telemetry_anomalies a" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<AnomalyDto> content = jdbc.query(ANOMALY_SELECT + where + " order by a.started_at desc, a.id desc limit ? offset ?", AnomalyService::anomalyDto,
                pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(content, p, s, t, (int) ((t + s - 1) / s));
    }

    @Transactional(readOnly = true)
    public AnomalyDto anomaly(Long id) {
        return jdbc.query(ANOMALY_SELECT + " where a.id = ?", AnomalyService::anomalyDto, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Anomaly", id));
    }

    // ------------------------------------------------------------------ detection

    /** Runs every enabled detector (each in its own transaction); returns the number of anomalies opened. */
    public int runAll() {
        Instant now = clock.instant();
        maintenance.processPending(now.minus(Duration.ofDays(1)), now.plusSeconds(60));
        int opened = 0;
        for (Long id : jdbc.queryForList("select id from anomaly_detectors where enabled order by id", Long.class)) {
            try {
                RunResult r = execute(id, now);
                opened += r == null ? 0 : r.anomaliesOpened();
            } catch (RuntimeException e) {
                log.error("Anomaly detector {} failed", id, e);
            }
        }
        return opened;
    }

    /** Runs one detector now (manual trigger; also used by tests). */
    public RunResult run(Long id) {
        get(id);
        Instant now = clock.instant();
        maintenance.processPending(now.minus(Duration.ofDays(1)), now.plusSeconds(60));
        return execute(id, now);
    }

    private RunResult execute(Long id, Instant now) {
        uncorrelated.get().clear();
        try {
            RunResult result = perDetector.execute(s -> runOne(id, now));
            for (Long anomalyId : List.copyOf(uncorrelated.get())) {
                try {
                    correlation.correlateAnomaly(anomalyId);
                } catch (RuntimeException e) {
                    log.error("Correlation of anomaly {} failed", anomalyId, e);
                }
            }
            return result;
        } finally {
            uncorrelated.remove();
        }
    }

    RunResult runOne(Long id, Instant now) {
        Detector d = detector(id);
        if (!d.enabled()) {
            return new RunResult(id, 0, 0, 0, 0, 0, 0, "DISABLED", "The detector is disabled");
        }
        List<SeriesRef> series = series(d);
        jdbc.update("update anomaly_detectors set last_run_at = ? where id = ?", Timestamp.from(now), id);
        if (series.isEmpty()) {
            return new RunResult(id, 0, 0, 0, 0, 0, 0, "NO_SERIES", "No series of metric " + d.metricName() + " match this detector"
                    + (d.includeSimulated() ? "" : " (simulated series excluded)"));
        }
        int points = 0;
        int opened = 0;
        int updated = 0;
        int resolved = 0;
        int alerts = 0;
        int insufficient = 0;
        int maxPoints = 0;
        Instant end = now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
        for (SeriesRef s : series) {
            boolean seasonal = seasonal(d, s, now);
            long lookbackMinutes = seasonal ? Duration.ofDays(15).toMinutes() : Math.max(180, 2L * d.window() + d.minSamples() + d.consecutive() + 10);
            Instant from = end.minus(Duration.ofMinutes(lookbackMinutes));
            List<Point> pts = load(s.id(), from, end, d.aggregation());
            points += pts.size();
            maxPoints = Math.max(maxPoints, pts.size());
            AnomalyEngine.Result result = AnomalyEngine.detect(pts, params(d, seasonal));
            if (!result.sufficient()) {
                insufficient++;
                continue;
            }
            int[] counts = reconcile(d, s, result.windows(), from, params(d, seasonal));
            opened += counts[0];
            updated += counts[1];
            resolved += counts[2];
            alerts += counts[3];
        }
        String status = insufficient == series.size() ? "INSUFFICIENT_DATA" : "OK";
        String message = insufficient == series.size() ? "Insufficient data — " + maxPoints + " of " + (d.minSamples() + 1) + " samples"
                : insufficient > 0 ? insufficient + " of " + series.size() + " series have insufficient data" : null;
        return new RunResult(id, series.size(), points, opened, updated, resolved, alerts, status, message);
    }

    /** Matches detection windows with stored anomalies; returns {opened, updated, resolved, alertsRaised}. */
    private int[] reconcile(Detector d, SeriesRef s, List<Window> windows, Instant from, Params params) {
        int opened = 0;
        int updated = 0;
        int resolved = 0;
        int alerts = 0;
        record Stored(long id, Instant start, Instant end, String status, double peak, Long alertId) {}
        List<Stored> stored = jdbc.query("select id, started_at, ended_at, status, peak_score, alert_id from telemetry_anomalies where detector_id = ? and "
                + "series_id = ? and coalesce(ended_at, last_point_at) >= ? order by started_at", (rs, n) -> new Stored(rs.getLong(1),
                rs.getTimestamp(2).toInstant(), MappingRuleService.instant(rs, 3), rs.getString(4), rs.getDouble(5), MappingRuleService.nullableLong(rs, 6)),
                d.id(), s.id(), Timestamp.from(from.minus(Duration.ofMinutes(5))));
        java.util.Set<Long> matched = new java.util.HashSet<>();
        Instant now = clock.instant();
        for (Window w : windows) {
            Stored hit = null;
            for (Stored st : stored) {
                Instant stEnd = st.end() == null ? Instant.MAX : st.end();
                if (!st.start().isAfter(w.end()) && !stEnd.isBefore(w.start())) {
                    hit = st;
                    break;
                }
            }
            String status = w.open() ? "OPEN" : "RESOLVED";
            if (hit == null) {
                Long anomalyId = jdbc.queryForObject("insert into telemetry_anomalies (detector_id, series_id, service_id, ci_id, metric_name, status, direction, "
                        + "started_at, ended_at, last_point_at, point_count, peak_score, observed_value, expected_value, expected_lower, expected_upper, "
                        + "model_params, simulated, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) returning id",
                        Long.class, d.id(), s.id(), serviceOf(d, s), ciOf(d, s), d.metricName(), status, w.direction(), Timestamp.from(w.start()),
                        w.open() ? null : Timestamp.from(w.end()), Timestamp.from(w.end()), w.points(), w.peakScore(), w.observed(), w.expected(), w.lower(),
                        w.upper(), TelemetryJson.write(params.describe()), s.simulated(), Timestamp.from(now), Timestamp.from(now));
                opened++;
                Long alertId = w.open() && d.raiseAlert() ? raiseAlert(d, s, anomalyId, w, true) : null;
                if (alertId != null) {
                    jdbc.update("update telemetry_anomalies set alert_id = ? where id = ?", alertId, anomalyId);
                    alerts++;
                } else if (w.open()) {
                    uncorrelated.get().add(anomalyId);
                }
                continue;
            }
            matched.add(hit.id());
            boolean resolving = "OPEN".equals(hit.status()) && !w.open();
            boolean reopening = "RESOLVED".equals(hit.status()) && w.open();
            // SET expressions see the old row, so the peak fields are replaced only when this window's peak is higher.
            jdbc.update("update telemetry_anomalies set status = ?, ended_at = ?, last_point_at = greatest(last_point_at, ?), point_count = greatest(point_count, ?), "
                    + "peak_score = greatest(peak_score, ?), observed_value = case when ? > peak_score then ? else observed_value end, expected_value = case when "
                    + "? > peak_score then ? else expected_value end, expected_lower = case when ? > peak_score then ? else expected_lower end, expected_upper = "
                    + "case when ? > peak_score then ? else expected_upper end, updated_at = ? where id = ?", status, w.open() ? null : Timestamp.from(w.end()),
                    Timestamp.from(w.end()), w.points(), w.peakScore(), w.peakScore(), w.observed(), w.peakScore(), w.expected(), w.peakScore(), w.lower(),
                    w.peakScore(), w.upper(), Timestamp.from(now), hit.id());
            updated++;
            if (resolving) {
                resolved++;
                if (hit.alertId() != null) {
                    raiseAlert(d, s, hit.id(), w, false);
                }
            } else if (reopening && d.raiseAlert()) {
                Long alertId = raiseAlert(d, s, hit.id(), w, true);
                if (alertId != null) {
                    jdbc.update("update telemetry_anomalies set alert_id = ? where id = ?", alertId, hit.id());
                    alerts++;
                }
            }
        }
        // Open anomalies that no current window covers any more ended before the evaluated range.
        for (Stored st : stored) {
            if ("OPEN".equals(st.status()) && !matched.contains(st.id())) {
                jdbc.update("update telemetry_anomalies set status = 'RESOLVED', ended_at = last_point_at, updated_at = ? where id = ?", Timestamp.from(now), st.id());
                resolved++;
                if (st.alertId() != null) {
                    raiseAlert(d, s, st.id(), null, false);
                }
            }
        }
        return new int[] {opened, updated, resolved, alerts};
    }

    /** Sends the anomaly through the alert pipeline (firing or resolved); returns the alert id. */
    private Long raiseAlert(Detector d, SeriesRef s, long anomalyId, Window w, boolean firing) {
        Long serviceId = serviceOf(d, s);
        Long ciId = ciOf(d, s);
        BusinessService service = serviceId == null ? null : services.findById(serviceId).orElse(null);
        ConfigurationItem ci = ciId == null ? null : cis.findById(ciId).orElse(null);
        String subject = service != null ? service.getName() : s.resourceServiceName() != null ? s.resourceServiceName() : "series " + s.id();
        DecimalFormat f = new DecimalFormat("0.###");
        String title = w == null ? "Anomaly: " + d.metricName() + " on " + subject
                : "Anomaly: " + d.metricName() + " " + (w.direction().equals("UP") ? "above" : "below") + " expected on " + subject + " (" + f.format(w.observed())
                + " vs " + f.format(w.lower()) + "–" + f.format(w.upper()) + ")";
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("alertname", "ObservabilityAnomaly");
        labels.put("source", "observability");
        labels.put("detector", d.name());
        labels.put("metric", d.metricName());
        labels.put("series", String.valueOf(s.id()));
        labels.put("anomaly", String.valueOf(anomalyId));
        if (service != null) {
            labels.put("service", service.getCode());
        }
        if (s.resourceServiceName() != null) {
            labels.put("service.name", s.resourceServiceName());
        }
        s.attributes().entrySet().stream().limit(5).forEach(e -> labels.putIfAbsent(e.getKey(), OtlpJsonParser.truncate(e.getValue(), 100)));
        Map<String, String> annotations = new LinkedHashMap<>();
        if (w != null) {
            annotations.put("expectedRange", f.format(w.lower()) + " – " + f.format(w.upper()));
            annotations.put("observed", f.format(w.observed()));
            annotations.put("score", f.format(w.peakScore()));
            annotations.put("model", d.algorithm() + " threshold " + d.threshold() + " for " + d.consecutive() + " consecutive points");
        }
        String description = w == null ? "The anomaly ended." : "Detector " + d.name() + " (" + d.algorithm() + ", |z| ≥ " + d.threshold() + " for "
                + d.consecutive() + " consecutive minutes): observed " + f.format(w.observed()) + ", expected " + f.format(w.expected()) + " (range "
                + f.format(w.lower()) + " – " + f.format(w.upper()) + "), score " + f.format(w.peakScore()) + ".";
        AlertIngestService.Origin origin = new AlertIngestService.Origin(Alert.SourceKind.OBSERVABILITY, null, null, service, ci, d.severity(),
                OBSERVABILITY_ACTOR, false, d.createIncident(), null, null, s.simulated());
        AlertSignal signal = new AlertSignal("anomaly:" + d.id() + ":" + s.id(), "observability:detector:" + d.id(), title, description, d.severity(), firing,
                labels, annotations, null, service == null ? null : service.getCode(), ci == null ? null : ci.getName(), w == null ? null : w.start(),
                firing ? null : clock.instant());
        AlertIngestService.Outcome outcome = alertIngest.ingest(origin, signal);
        return outcome.alert() == null ? null : outcome.alert().getId();
    }

    // ------------------------------------------------------------------ evaluation

    /** Precision / recall of the detector on labelled history (see {@link EvaluateRequest}); stored as the detector's last evaluation. */
    @Transactional
    public EvaluationResult evaluate(Long id, EvaluateRequest r) {
        Detector d = detector(id);
        Instant now = clock.instant();
        Instant to = r == null || r.to() == null ? now : r.to();
        Instant from = r == null || r.from() == null ? to.minus(Duration.ofDays(7)) : r.from();
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("from must be before to");
        }
        if (Duration.between(from, to).toDays() > 31) {
            throw ApiException.badRequest("The evaluation period is limited to 31 days");
        }
        maintenance.processPending(from, to);
        int tolerance = r == null || r.toleranceMinutes() == null ? 5 : r.toleranceMinutes();
        Duration tol = Duration.ofMinutes(tolerance);
        List<SeriesRef> series = series(d);
        List<WindowDto> detections = new ArrayList<>();
        int points = 0;
        int maxPoints = 0;
        boolean anySufficient = false;
        java.util.Set<Long> serviceIds = new java.util.LinkedHashSet<>();
        Params last = params(d, false);
        for (SeriesRef s : series) {
            if (serviceOf(d, s) != null) {
                serviceIds.add(serviceOf(d, s));
            }
            boolean seasonal = seasonal(d, s, to);
            last = params(d, seasonal);
            List<Point> pts = load(s.id(), from, to, d.aggregation());
            points += pts.size();
            maxPoints = Math.max(maxPoints, pts.size());
            AnomalyEngine.Result result = AnomalyEngine.detect(pts, last);
            anySufficient |= result.sufficient();
            for (Window w : result.windows()) {
                detections.add(new WindowDto(s.id(), w.start(), w.end(), round(w.peakScore()), w.observed(), w.expected()));
            }
        }
        List<LabelDto> labels = new ArrayList<>();
        boolean useFaults = r == null || r.useSimulatedFaults() == null ? d.includeSimulated() : r.useSimulatedFaults();
        boolean useAlerts = r == null || r.useAlerts() == null || r.useAlerts();
        if (useFaults) {
            List<Object> args = new ArrayList<>(List.of(Timestamp.from(now), Timestamp.from(to), Timestamp.from(from), d.metricName()));
            String services = serviceIds.isEmpty() ? "" : " or service_id in (" + String.join(",", serviceIds.stream().map(String::valueOf).toList()) + ")";
            jdbc.query("select id, label, started_at, coalesce(ended_at, ?) from simulation_fault_windows where started_at < ? and coalesce(ended_at, now()) > ? "
                    + "and (metric_name = ?" + services + ")", rs -> {
                        labels.add(new LabelDto("FAULT_WINDOW", "fault-window:" + rs.getLong(1) + " " + rs.getString(2), rs.getTimestamp(3).toInstant(),
                                rs.getTimestamp(4).toInstant(), false));
                    }, args.toArray());
        }
        if (useAlerts && !serviceIds.isEmpty()) {
            jdbc.query("select number, coalesce(starts_at, first_seen_at), coalesce(resolved_at, ends_at, last_seen_at) from alerts where service_id in ("
                    + String.join(",", serviceIds.stream().map(String::valueOf).toList()) + ") and fingerprint not like ? and coalesce(starts_at, first_seen_at) < ? "
                    + "and coalesce(resolved_at, ends_at, last_seen_at) > ?" + (d.includeSimulated() ? "" : " and not simulated") + " order by 2 limit 200", rs -> {
                        labels.add(new LabelDto("ALERT", rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(), false));
                    }, "anomaly:" + d.id() + ":%", Timestamp.from(to), Timestamp.from(from));
        }
        if (r != null && r.labels() != null) {
            for (LabelWindow l : r.labels()) {
                if (!l.from().isBefore(l.to()) && !l.from().equals(l.to())) {
                    throw ApiException.badRequest("Each label needs from <= to");
                }
                labels.add(new LabelDto("MANUAL", l.label() == null ? "manual" : l.label(), l.from(), l.to(), false));
            }
        }
        int tpDetections = 0;
        for (WindowDto w : detections) {
            if (labels.stream().anyMatch(l -> overlaps(w.from(), w.to(), l.from(), l.to(), tol))) {
                tpDetections++;
            }
        }
        List<LabelDto> markedLabels = labels.stream().map(l -> new LabelDto(l.source(), l.reference(), l.from(), l.to(),
                detections.stream().anyMatch(w -> overlaps(w.from(), w.to(), l.from(), l.to(), tol)))).toList();
        int detectedLabels = (int) markedLabels.stream().filter(LabelDto::detected).count();
        Double precision = detections.isEmpty() || markedLabels.isEmpty() ? null : round((double) tpDetections / detections.size());
        Double recall = markedLabels.isEmpty() ? null : round((double) detectedLabels / markedLabels.size());
        Double f1 = precision == null || recall == null || precision + recall == 0 ? null : round(2 * precision * recall / (precision + recall));
        String status;
        String message;
        if (!anySufficient) {
            status = "INSUFFICIENT_DATA";
            message = "Insufficient data — " + maxPoints + " of " + (d.minSamples() + 1) + " samples in the period";
        } else if (markedLabels.isEmpty()) {
            status = "INSUFFICIENT_DATA";
            message = "No labelled fault windows or alerts in the period: precision and recall cannot be computed";
        } else {
            status = "EVALUATED";
            message = detections.size() + " detection(s), " + markedLabels.size() + " label(s), tolerance " + tolerance + " min";
        }
        Map<String, Object> params = new LinkedHashMap<>(last.describe());
        params.put("valueAggregation", d.aggregation());
        params.put("toleranceMinutes", tolerance);
        EvaluationResult result = new EvaluationResult(id, status, message, from, to, series.size(), points, detections, markedLabels, tpDetections,
                detections.size() - tpDetections, markedLabels.size() - detectedLabels, precision, recall, f1, params, now);
        jdbc.update("update anomaly_detectors set last_evaluation = ?::jsonb, last_evaluated_at = ? where id = ?", TelemetryJson.write(result),
                Timestamp.from(now), id);
        audit.record("ANOMALY_DETECTOR_EVALUATED", "ANOMALY_DETECTOR", id, Map.of("status", status, "detections", detections.size(), "labels",
                markedLabels.size(), "precision", precision == null ? "n/a" : precision, "recall", recall == null ? "n/a" : recall));
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean overlaps(Instant aFrom, Instant aTo, Instant bFrom, Instant bTo, Duration tol) {
        return !aFrom.isAfter(bTo.plus(tol)) && !aTo.isBefore(bFrom.minus(tol));
    }

    private static Params params(Detector d, boolean seasonal) {
        return new Params(AnomalyEngine.Algorithm.valueOf(d.algorithm()), d.window(), d.threshold(), d.consecutive(), d.minSamples(),
                AnomalyEngine.Direction.valueOf(d.direction()), seasonal);
    }

    /** Hour-of-week seasonality: forced, off, or AUTO when the series has at least two weeks of history. */
    private static boolean seasonal(Detector d, SeriesRef s, Instant now) {
        return switch (d.seasonality()) {
            case "HOUR_OF_WEEK" -> true;
            case "NONE" -> false;
            default -> s.firstSeen() != null && !s.firstSeen().isAfter(now.minus(Duration.ofDays(14)));
        };
    }

    private List<Point> load(long seriesId, Instant from, Instant to, String aggregation) {
        List<PointDto> pts = queries.points(seriesId, from, to, 60, aggregation);
        List<Point> out = new ArrayList<>(pts.size());
        for (PointDto p : pts) {
            out.add(new Point(p.t(), p.v()));
        }
        return out;
    }

    private static Long serviceOf(Detector d, SeriesRef s) {
        return s.serviceId() != null ? s.serviceId() : d.serviceId();
    }

    private static Long ciOf(Detector d, SeriesRef s) {
        return s.ciId() != null ? s.ciId() : d.ciId();
    }

    private List<SeriesRef> series(Detector d) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where s.name = ?");
        args.add(d.metricName());
        if (!d.includeSimulated()) {
            where.append(" and not s.simulated");
        }
        if (d.serviceId() != null) {
            where.append(" and r.service_id = ?");
            args.add(d.serviceId());
        }
        if (d.ciId() != null) {
            where.append(" and r.ci_id = ?");
            args.add(d.ciId());
        }
        if (!d.filters().isEmpty()) {
            where.append(" and s.attributes @> ?::jsonb");
            args.add(TelemetryJson.write(d.filters()));
        }
        args.add(MAX_SERIES_PER_DETECTOR);
        return jdbc.query("select s.id, s.attributes::text, r.service_id, r.ci_id, r.service_name, s.simulated, s.first_seen_at from telemetry_metric_series s "
                + "join telemetry_resources r on r.id = s.resource_id" + where + " order by s.id limit ?", (rs, n) -> new SeriesRef(rs.getLong(1),
                TelemetryJson.stringMap(rs.getString(2)), MappingRuleService.nullableLong(rs, 3), MappingRuleService.nullableLong(rs, 4), rs.getString(5),
                rs.getBoolean(6), MappingRuleService.instant(rs, 7)), args.toArray());
    }

    private Detector detector(Long id) {
        return jdbc.query("select id, name, metric_name, service_id, ci_id, attribute_filters::text, value_aggregation, algorithm, window_points, threshold, "
                + "consecutive_points, min_samples, direction, seasonality, include_simulated, raise_alert, alert_severity, create_incident, enabled from "
                + "anomaly_detectors where id = ?", (rs, n) -> new Detector(rs.getLong(1), rs.getString(2), rs.getString(3), MappingRuleService.nullableLong(rs, 4),
                MappingRuleService.nullableLong(rs, 5), TelemetryJson.stringMap(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getInt(9),
                rs.getDouble(10), rs.getInt(11), rs.getInt(12), rs.getString(13), rs.getString(14), rs.getBoolean(15), rs.getBoolean(16),
                AlertSeverity.valueOf(rs.getString(17)), rs.getBoolean(18), rs.getBoolean(19)), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Anomaly detector", id));
    }

    private static Double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static final String DETECTOR_SELECT = "select d.id, d.name, d.description, d.metric_name, d.service_id, s.name, d.ci_id, c.name, "
            + "d.attribute_filters::text, d.value_aggregation, d.algorithm, d.window_points, d.threshold, d.consecutive_points, d.min_samples, d.direction, "
            + "d.seasonality, d.include_simulated, d.raise_alert, d.alert_severity, d.create_incident, d.enabled, d.last_run_at, d.last_evaluation::text, "
            + "d.last_evaluated_at, (select count(*) from telemetry_anomalies a where a.detector_id = d.id and a.status = 'OPEN'), d.is_demo, d.created_at, "
            + "d.updated_at from anomaly_detectors d left join business_services s on s.id = d.service_id left join configuration_items c on c.id = d.ci_id";

    private DetectorDto detectorDto(ResultSet rs, int n) throws SQLException {
        return new DetectorDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), MappingRuleService.nullableLong(rs, 5), rs.getString(6),
                MappingRuleService.nullableLong(rs, 7), rs.getString(8), TelemetryJson.stringMap(rs.getString(9)), rs.getString(10), rs.getString(11),
                rs.getInt(12), rs.getDouble(13), rs.getInt(14), rs.getInt(15), rs.getString(16), rs.getString(17), rs.getBoolean(18), rs.getBoolean(19),
                AlertSeverity.valueOf(rs.getString(20)), rs.getBoolean(21), rs.getBoolean(22), MappingRuleService.instant(rs, 23),
                TelemetryJson.read(rs.getString(24), EvaluationResult.class), MappingRuleService.instant(rs, 25), rs.getLong(26), rs.getBoolean(27),
                MappingRuleService.instant(rs, 28), MappingRuleService.instant(rs, 29));
    }

    private static final String ANOMALY_SELECT = "select a.id, a.detector_id, d.name, a.series_id, a.metric_name, ms.attributes::text, a.service_id, s.name, "
            + "a.ci_id, c.name, a.status, a.direction, a.started_at, a.ended_at, a.last_point_at, a.point_count, a.peak_score, a.observed_value, "
            + "a.expected_value, a.expected_lower, a.expected_upper, a.model_params::text, a.alert_id, al.number, (select m.group_id from "
            + "correlation_group_members m where m.anomaly_id = a.id or (a.alert_id is not null and m.alert_id = a.alert_id) limit 1), a.simulated, a.created_at "
            + "from telemetry_anomalies a join anomaly_detectors d on d.id = a.detector_id join telemetry_metric_series ms on ms.id = a.series_id "
            + "left join business_services s on s.id = a.service_id left join configuration_items c on c.id = a.ci_id left join alerts al on al.id = a.alert_id";

    private static AnomalyDto anomalyDto(ResultSet rs, int n) throws SQLException {
        return new AnomalyDto(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), TelemetryJson.stringMap(rs.getString(6)),
                MappingRuleService.nullableLong(rs, 7), rs.getString(8), MappingRuleService.nullableLong(rs, 9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getTimestamp(13).toInstant(), MappingRuleService.instant(rs, 14), rs.getTimestamp(15).toInstant(), rs.getInt(16),
                rs.getDouble(17), rs.getDouble(18), rs.getDouble(19), rs.getDouble(20), rs.getDouble(21), TelemetryJson.objectMap(rs.getString(22)),
                MappingRuleService.nullableLong(rs, 23), rs.getString(24), MappingRuleService.nullableLong(rs, 25), rs.getBoolean(26),
                rs.getTimestamp(27).toInstant());
    }
}

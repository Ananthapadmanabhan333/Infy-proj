package com.nexus360.prediction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.observability.AnomalyService;
import com.nexus360.prediction.ModelTrainer.Outcome;
import com.nexus360.prediction.ModelTrainer.Window;
import com.nexus360.prediction.PredictionDtos.EvaluateRequest;
import com.nexus360.prediction.PredictionDtos.JobDto;
import com.nexus360.prediction.PredictionDtos.ModelDto;
import com.nexus360.prediction.PredictionDtos.ModelStatus;
import com.nexus360.prediction.PredictionDtos.ModelType;
import com.nexus360.prediction.PredictionDtos.TrainRequest;
import com.nexus360.security.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Model registry of SA Predictive Intelligence: explicit training and evaluation jobs (prediction administrators) and the weekly
 * scheduled retraining, model versions per type (per detector for ANOMALY), activation with a single ACTIVE version per type, and
 * retirement. Training never activates a model; a human does. Simulated records are excluded from training unless the request sets
 * {@code includeSimulated: true}, in which case the model is flagged {@code simulated}. Registry writes are serialised by the
 * transaction-scoped advisory lock 360019. Every training, evaluation, activation and retirement is audited.
 */
@Slf4j
@Service
public class PredictionModelService {

    static final long REGISTRY_LOCK = 360_019L;
    static final int MAX_WINDOW_DAYS = 730;

    private final JdbcTemplate jdbc;
    private final ModelTrainer trainer;
    private final PredictionDatasets data;
    private final AnomalyService anomalies;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PredictionModelService(JdbcTemplate jdbc, ModelTrainer trainer, PredictionDatasets data, AnomalyService anomalies, AuditService audit,
                                  ObjectMapper json, Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.trainer = trainer;
        this.data = data;
        this.anomalies = anomalies;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Internal view of a stored model. */
    public record Model(long id, ModelType type, String scopeKey, int version, String algorithm, ModelStatus status, String statusMessage,
                        List<String> features, Map<String, Object> params, Map<String, Object> metrics, Instant trainingFrom, Instant trainingTo,
                        Instant cutoffAt, int sampleCount, Integer positiveCount, int trainCount, int testCount, int minSamples, boolean includeSimulated,
                        boolean simulated, Long detectorId, Instant trainedAt, String trainedByName, Instant activatedAt, String activatedByName,
                        Instant retiredAt, String retiredByName, Instant lastEvaluatedAt, String driftStatus, Instant driftCheckedAt, Instant createdAt,
                        Instant updatedAt) {}

    // ------------------------------------------------------------------ training

    public ModelDto train(TrainRequest r, Actor actor, String trigger) {
        Instant started = clock.instant();
        Window w = window(r.type(), r.from(), r.to(), started);
        boolean includeSimulated = Boolean.TRUE.equals(r.includeSimulated());
        double testFraction = r.testFraction() == null ? ModelTrainer.DEFAULT_TEST_FRACTION : r.testFraction();
        double l2 = r.l2() == null ? ModelTrainer.DEFAULT_L2 : r.l2();
        String scope = "";
        Long detectorId = null;
        if (r.type() == ModelType.ANOMALY) {
            if (r.detectorId() == null) {
                throw ApiException.invalidField("detectorId", "detectorId is required for ANOMALY registrations");
            }
            var detector = anomalies.get(r.detectorId());
            if (detector.includeSimulated() && !includeSimulated) {
                throw ApiException.badRequest("Detector '" + detector.name() + "' evaluates simulated series; set includeSimulated to true");
            }
            detectorId = detector.id();
            scope = "detector:" + detectorId;
        } else if (r.detectorId() != null) {
            throw ApiException.invalidField("detectorId", "detectorId applies to ANOMALY only");
        }
        Outcome outcome;
        try {
            outcome = switch (r.type()) {
                case SLA_BREACH -> trainer.trainSla(w, includeSimulated, testFraction, l2);
                case INCIDENT_RECURRENCE -> trainer.trainRecurrence(w, includeSimulated, testFraction, l2);
                case CAPACITY_RISK -> trainer.trainCapacity(w, includeSimulated, testFraction);
                case SERVICE_HEALTH -> trainer.trainServiceHealth(w, includeSimulated, testFraction);
                case ANOMALY -> trainer.registerAnomaly(r.detectorId(), w, includeSimulated, r.labels(), r.useAlerts(), r.toleranceMinutes());
            };
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Training of a {} model failed", r.type(), e);
            outcome = new Outcome(ModelStatus.FAILED, "Training failed: " + e.getClass().getSimpleName(), "n/a", List.of(), Map.of(), Map.of(), null, 0, null,
                    0, 0, 0);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", r.type().name());
        request.put("from", w.from().toString());
        request.put("to", w.to().toString());
        request.put("includeSimulated", includeSimulated);
        request.put("testFraction", testFraction);
        if (r.type() == ModelType.SLA_BREACH || r.type() == ModelType.INCIDENT_RECURRENCE) {
            request.put("l2", l2);
        }
        if (detectorId != null) {
            request.put("detectorId", detectorId);
        }
        Outcome o = outcome;
        String scopeKey = scope;
        Long detector = detectorId;
        long id = tx.execute(s -> {
            lock();
            int version = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from prediction_models where model_type = ? and scope_key = ?",
                    Integer.class, r.type().name(), scopeKey);
            Instant now = clock.instant();
            Long modelId = jdbc.queryForObject("insert into prediction_models (model_type, scope_key, version, algorithm, status, status_message, features, "
                    + "params, metrics, training_from, training_to, cutoff_at, sample_count, positive_count, train_count, test_count, min_samples, "
                    + "include_simulated, simulated, detector_id, trained_at, trained_by_id, trained_by_name, created_at, updated_at) values "
                    + "(?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", Long.class,
                    r.type().name(), scopeKey, version, o.algorithm(), o.status().name(), truncate(o.message(), 1000), write(o.features()), write(o.params()),
                    write(o.metrics()), ts(w.from()), ts(w.to()), ts(o.cutoff()), o.samples(), o.positives(), o.train(), o.test(), o.minSamples(),
                    includeSimulated, includeSimulated, detector, ts(now), actor.id(), actor.name(), ts(now), ts(now));
            job("TRAIN", trigger, r.type(), modelId, o.status() == ModelStatus.TRAINED ? "SUCCEEDED" : o.status().name(), o.message(), request,
                    Map.of("status", o.status().name(), "version", version, "metrics", o.metrics()), started, actor);
            return modelId;
        });
        Map<String, Object> details = new LinkedHashMap<>(request);
        details.put("status", outcome.status().name());
        details.put("samples", outcome.samples());
        audit.record("PREDICTION_MODEL_TRAINED", "PREDICTION_MODEL", id, details);
        return dto(load(id));
    }

    // ------------------------------------------------------------------ evaluation

    /** Out-of-time evaluation of a stored model on a new window (no refit); recorded as an EVALUATE job. */
    public JobDto evaluate(Long id, EvaluateRequest r, Actor actor) {
        Model m = load(id);
        if (m.status() == ModelStatus.INSUFFICIENT_DATA || m.status() == ModelStatus.FAILED) {
            throw ApiException.conflict("Model " + m.type() + " v" + m.version() + " has no fitted parameters (" + m.status() + ")");
        }
        Instant started = clock.instant();
        Instant to = r == null || r.to() == null ? started : r.to();
        Instant from = r == null || r.from() == null ? defaultEvaluationStart(m, to) : r.from();
        Window w = validate(from, to);
        Map<String, Object> result = switch (m.type()) {
            case SLA_BREACH -> trainer.evaluateClassifier(m.params(), PredictionDatasets.slaSamples(data.slaIncidents(w.from(), w.to(), m.includeSimulated())));
            case INCIDENT_RECURRENCE -> trainer.evaluateClassifier(m.params(),
                    PredictionDatasets.recurrenceSamples(data.recurrenceRows(w.from(), w.to(), started, m.includeSimulated())));
            case CAPACITY_RISK -> trainer.evaluateCapacity(m.params(), w, m.includeSimulated());
            case SERVICE_HEALTH -> trainer.evaluateServiceHealth(m.params(), w, m.includeSimulated());
            case ANOMALY -> trainer.evaluateAnomaly(m.detectorId(), w, m.includeSimulated(), r == null ? null : r.labels(), r == null ? null : r.useAlerts(),
                    r == null ? null : r.toleranceMinutes());
        };
        String status = "EVALUATED".equals(result.get("status")) ? "SUCCEEDED" : "INSUFFICIENT_DATA";
        Map<String, Object> request = Map.of("from", w.from().toString(), "to", w.to().toString());
        long jobId = tx.execute(s -> {
            jdbc.update("update prediction_models set last_evaluated_at = ?, updated_at = ? where id = ?", ts(clock.instant()), ts(clock.instant()), id);
            return job("EVALUATE", "MANUAL", m.type(), id, status, (String) result.getOrDefault("message", "Evaluated " + m.type() + " v" + m.version()),
                    request, result, started, actor);
        });
        audit.record("PREDICTION_MODEL_EVALUATED", "PREDICTION_MODEL", id, Map.of("from", w.from().toString(), "to", w.to().toString(), "status", status));
        return job(jobId);
    }

    private static Instant defaultEvaluationStart(Model m, Instant to) {
        Instant start = m.trainingTo();
        Duration min = m.type() == ModelType.ANOMALY ? Duration.ofDays(7) : Duration.ofDays(30);
        if (!start.isBefore(to.minus(Duration.ofHours(1)))) {
            start = to.minus(min);
        }
        if (m.type() == ModelType.ANOMALY && Duration.between(start, to).toDays() > 31) {
            start = to.minus(Duration.ofDays(31));
        }
        return start;
    }

    // ------------------------------------------------------------------ lifecycle

    public ModelDto activate(Long id, Actor actor) {
        Model previous = tx.execute(s -> {
            lock();
            Model m = load(id);
            if (m.status() == ModelStatus.ACTIVE) {
                throw ApiException.conflict(m.type() + " v" + m.version() + " is already active");
            }
            if (m.status() != ModelStatus.TRAINED && m.status() != ModelStatus.RETIRED) {
                throw ApiException.conflict("Only a TRAINED or RETIRED model can be activated; " + m.type() + " v" + m.version() + " is " + m.status());
            }
            Instant now = clock.instant();
            List<Long> active = jdbc.queryForList("select id from prediction_models where model_type = ? and scope_key = ? and status = 'ACTIVE'", Long.class,
                    m.type().name(), m.scopeKey());
            jdbc.update("update prediction_models set status = 'RETIRED', retired_at = ?, retired_by_name = ?, updated_at = ? where model_type = ? "
                    + "and scope_key = ? and status = 'ACTIVE'", ts(now), actor.name(), ts(now), m.type().name(), m.scopeKey());
            jdbc.update("update prediction_models set status = 'ACTIVE', activated_at = ?, activated_by_name = ?, retired_at = null, retired_by_name = null, "
                    + "drift_status = null, drift_checked_at = null, updated_at = ? where id = ?", ts(now), actor.name(), ts(now), id);
            return active.isEmpty() ? null : load(active.get(0));
        });
        Model m = load(id);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", m.type().name());
        details.put("version", m.version());
        details.put("replacedVersion", previous == null ? "" : previous.version());
        audit.record("PREDICTION_MODEL_ACTIVATED", "PREDICTION_MODEL", id, details);
        return dto(m);
    }

    public ModelDto retire(Long id, Actor actor) {
        tx.executeWithoutResult(s -> {
            lock();
            Model m = load(id);
            if (m.status() != ModelStatus.ACTIVE && m.status() != ModelStatus.TRAINED) {
                throw ApiException.conflict(m.type() + " v" + m.version() + " is " + m.status() + " and cannot be retired");
            }
            Instant now = clock.instant();
            jdbc.update("update prediction_models set status = 'RETIRED', retired_at = ?, retired_by_name = ?, updated_at = ? where id = ?", ts(now),
                    actor.name(), ts(now), id);
        });
        Model m = load(id);
        audit.record("PREDICTION_MODEL_RETIRED", "PREDICTION_MODEL", id, Map.of("type", m.type().name(), "version", m.version()));
        return dto(m);
    }

    // ------------------------------------------------------------------ queries

    public List<ModelDto> list(ModelType type, ModelStatus status) {
        StringBuilder sql = new StringBuilder("select * from prediction_models where true");
        List<Object> args = new java.util.ArrayList<>();
        if (type != null) {
            sql.append(" and model_type = ?");
            args.add(type.name());
        }
        if (status != null) {
            sql.append(" and status = ?");
            args.add(status.name());
        }
        sql.append(" order by model_type, scope_key, version desc limit 500");
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), args.toArray()).stream().map(this::dto).toList();
    }

    public ModelDto get(Long id) {
        return dto(load(id));
    }

    public Model load(Long id) {
        return jdbc.query("select * from prediction_models where id = ?", (rs, n) -> map(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Prediction model", id));
    }

    /** The ACTIVE model of a type (ANOMALY: of one detector). */
    public Optional<Model> active(ModelType type, String scopeKey) {
        return jdbc.query("select * from prediction_models where model_type = ? and scope_key = ? and status = 'ACTIVE'", (rs, n) -> map(rs), type.name(),
                scopeKey == null ? "" : scopeKey).stream().findFirst();
    }

    public List<Model> activeModels() {
        return jdbc.query("select * from prediction_models where status = 'ACTIVE' order by id", (rs, n) -> map(rs));
    }

    public List<JobDto> jobs(Long modelId, Integer limit) {
        int n = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        return modelId == null
                ? jdbc.query("select * from prediction_jobs order by created_at desc, id desc limit ?", (rs, i) -> jobDto(rs), n)
                : jdbc.query("select * from prediction_jobs where model_id = ? order by created_at desc, id desc limit ?", (rs, i) -> jobDto(rs), modelId, n);
    }

    public JobDto job(long id) {
        return jdbc.query("select * from prediction_jobs where id = ?", (rs, i) -> jobDto(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Prediction job", id));
    }

    /** Evaluate jobs recorded after {@code since} (ANOMALY live performance). */
    public List<JobDto> evaluationsSince(long modelId, Instant since) {
        return jdbc.query("select * from prediction_jobs where model_id = ? and job_type = 'EVALUATE' and status = 'SUCCEEDED' and created_at >= ? "
                + "order by created_at", (rs, i) -> jobDto(rs), modelId, ts(since));
    }

    void setDrift(long id, String status) {
        jdbc.update("update prediction_models set drift_status = ?, drift_checked_at = ? where id = ?", status, ts(clock.instant()), id);
    }

    // ------------------------------------------------------------------ helpers

    private Window window(ModelType type, Instant from, Instant to, Instant now) {
        Instant t = to == null ? now : to;
        Duration span = switch (type) {
            case ANOMALY -> Duration.ofDays(7);
            case CAPACITY_RISK -> Duration.ofDays(90);
            default -> Duration.ofDays(365);
        };
        return validate(from == null ? t.minus(span) : from, t);
    }

    private Window validate(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("'from' must be before 'to'");
        }
        if (Duration.between(from, to).toDays() > MAX_WINDOW_DAYS) {
            throw ApiException.badRequest("The window may cover at most " + MAX_WINDOW_DAYS + " days");
        }
        if (to.isAfter(clock.instant().plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("'to' must not be in the future");
        }
        return new Window(from, to);
    }

    private void lock() {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> { }, REGISTRY_LOCK);
    }

    private long job(String type, String trigger, ModelType modelType, Long modelId, String status, String message, Map<String, Object> request,
                     Map<String, Object> result, Instant started, Actor actor) {
        Instant now = clock.instant();
        return jdbc.queryForObject("insert into prediction_jobs (job_type, trigger_type, model_type, model_id, status, message, request, result, started_at, "
                + "finished_at, duration_ms, requested_by_id, requested_by_name, created_at) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?) "
                + "returning id", Long.class, type, trigger, modelType.name(), modelId, status, truncate(message, 1000), write(request), write(result),
                ts(started), ts(now), Math.max(0, Duration.between(started, now).toMillis()), actor.id(), actor.name(), ts(now));
    }

    ModelDto dto(Model m) {
        return new ModelDto(m.id(), m.type(), m.scopeKey(), m.version(), m.algorithm(), m.status(), m.statusMessage(), m.features(), m.params(), m.metrics(),
                m.trainingFrom(), m.trainingTo(), m.cutoffAt(), m.sampleCount(), m.positiveCount(), m.trainCount(), m.testCount(), m.minSamples(),
                m.includeSimulated(), m.simulated(), m.detectorId(), m.trainedAt(), m.trainedByName(), m.activatedAt(), m.activatedByName(), m.retiredAt(),
                m.retiredByName(), m.lastEvaluatedAt(), m.driftStatus(), m.driftCheckedAt(), method(m.type()), m.createdAt(), m.updatedAt());
    }

    /** Plain-language description of how a model type computes its predictions (shown with every prediction; never presented as AI). */
    public static String method(ModelType type) {
        return switch (type) {
            case SLA_BREACH -> "Statistical model, not AI: L2-regularised logistic regression on standardised features (priority, category, service tier, "
                    + "team load, time of day, elapsed share of the resolution target, reassignments) trained on completed resolution clocks; "
                    + "probability that the running clock breaches";
            case INCIDENT_RECURRENCE -> "Statistical model, not AI: L2-regularised logistic regression (past recurrence, linked problem, resolution code, "
                    + "change since, priority, service tier) trained on resolved incidents; probability that the same service and category recur within "
                    + PredictionDatasets.RECURRENCE_DAYS + " days";
            case CAPACITY_RISK -> "Statistical forecast, not AI: Holt linear trend on hourly utilisation with a 95% prediction interval; time until the "
                    + "point forecast and the interval bounds reach the threshold";
            case SERVICE_HEALTH -> "Statistical forecast, not AI: additive Holt-Winters (weekly season) on daily incident and alert counts — seasonal naive "
                    + "below two weeks of history — with 95% prediction intervals";
            case ANOMALY -> "Observability anomaly detector (EWMA or median/MAD baseline, z-score threshold) registered for evaluation tracking: "
                    + "precision/recall against labelled windows";
        };
    }

    private Model map(ResultSet rs) throws SQLException {
        return new Model(rs.getLong("id"), ModelType.valueOf(rs.getString("model_type")), rs.getString("scope_key"), rs.getInt("version"),
                rs.getString("algorithm"), ModelStatus.valueOf(rs.getString("status")), rs.getString("status_message"),
                read(rs.getString("features"), new TypeReference<List<String>>() { }), read(rs.getString("params"), new TypeReference<Map<String, Object>>() { }),
                read(rs.getString("metrics"), new TypeReference<Map<String, Object>>() { }), inst(rs, "training_from"), inst(rs, "training_to"),
                inst(rs, "cutoff_at"), rs.getInt("sample_count"), (Integer) rs.getObject("positive_count"), rs.getInt("train_count"), rs.getInt("test_count"),
                rs.getInt("min_samples"), rs.getBoolean("include_simulated"), rs.getBoolean("simulated"), (Long) rs.getObject("detector_id"),
                inst(rs, "trained_at"), rs.getString("trained_by_name"), inst(rs, "activated_at"), rs.getString("activated_by_name"), inst(rs, "retired_at"),
                rs.getString("retired_by_name"), inst(rs, "last_evaluated_at"), rs.getString("drift_status"), inst(rs, "drift_checked_at"),
                inst(rs, "created_at"), inst(rs, "updated_at"));
    }

    private JobDto jobDto(ResultSet rs) throws SQLException {
        return new JobDto(rs.getLong("id"), rs.getString("job_type"), rs.getString("trigger_type"), ModelType.valueOf(rs.getString("model_type")),
                (Long) rs.getObject("model_id"), rs.getString("status"), rs.getString("message"),
                read(rs.getString("request"), new TypeReference<Map<String, Object>>() { }), read(rs.getString("result"), new TypeReference<Map<String, Object>>() { }),
                inst(rs, "started_at"), inst(rs, "finished_at"), rs.getLong("duration_ms"), rs.getString("requested_by_name"));
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise prediction data", e);
        }
    }

    <T> T read(String text, TypeReference<T> type) {
        try {
            return text == null ? null : json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read prediction data", e);
        }
    }

    static Instant inst(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

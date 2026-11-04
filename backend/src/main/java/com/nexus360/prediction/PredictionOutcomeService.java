package com.nexus360.prediction;

import com.nexus360.admin.SettingsService;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.prediction.PredictionDtos.JobDto;
import com.nexus360.prediction.PredictionDtos.ModelType;
import com.nexus360.prediction.PredictionDtos.Performance;
import com.nexus360.prediction.PredictionDtos.ReconcileResult;
import com.nexus360.prediction.PredictionMath.ForecastErrors;
import com.nexus360.prediction.PredictionModelService.Model;
import com.nexus360.user.Role;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outcome recording and live performance. {@link #reconcile()} (scheduled under advisory lock 360017, or explicit) records the outcome of
 * every pending prediction once it is known from stored data — SLA_BREACH when the resolution clock completes (MISSED = positive),
 * INCIDENT_RECURRENCE when the same service and category recur or the 14-day window closes, CAPACITY_RISK when the hourly roll-up of the
 * target hour exists, SERVICE_HEALTH when the target day is over — and then compares each ACTIVE model's live metrics with its offline
 * evaluation. A degradation beyond the tolerances (settings {@code predictions.drift.*}) sets {@code driftStatus = DRIFT} and notifies the
 * prediction administrators (notification type {@code PREDICTION_DRIFT}) when the status changes.
 */
@Slf4j
@Service
public class PredictionOutcomeService {

    static final Duration CAPACITY_EXPIRY = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final PredictionModelService models;
    private final PredictionDatasets data;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PredictionOutcomeService(JdbcTemplate jdbc, PredictionModelService models, PredictionDatasets data, SettingsService settings,
                                    NotificationService notifications, Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.models = models;
        this.data = data;
        this.settings = settings;
        this.notifications = notifications;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------ outcomes

    public ReconcileResult reconcile() {
        Instant now = clock.instant();
        int[] counts = new int[3];
        tx.executeWithoutResult(s -> {
            counts[1] += jdbc.update("update predictions p set outcome_status = 'KNOWN', outcome_label = (s.outcome = 'MISSED'), "
                    + "outcome_value = case when s.outcome = 'MISSED' then 1 else 0 end, outcome_at = s.completed_at, outcome_note = 'Resolution clock ' || s.outcome "
                    + "from incident_slas s where p.model_type = 'SLA_BREACH' and p.outcome_status = 'PENDING' and s.incident_id = p.subject_id "
                    + "and s.outcome in ('MET', 'MISSED')");
            counts[2] += jdbc.update("update predictions p set outcome_status = 'EXPIRED', outcome_note = 'The incident or its SLA clock no longer exists', "
                    + "outcome_at = ? where p.model_type = 'SLA_BREACH' and p.outcome_status = 'PENDING' and not exists (select 1 from incident_slas s "
                    + "where s.incident_id = p.subject_id)", Timestamp.from(now));
            counts[2] += jdbc.update("update predictions p set outcome_status = 'EXPIRED', outcome_note = 'Clock cancelled or completed without a MET/MISSED outcome', "
                    + "outcome_at = ? from incident_slas s where p.model_type = 'SLA_BREACH' and p.outcome_status = 'PENDING' and s.incident_id = p.subject_id "
                    + "and s.clock_status = 'STOPPED' and s.outcome is not null and s.outcome not in ('MET', 'MISSED')", Timestamp.from(now));
            // Recurrence: positive as soon as a matching incident appears in the window, negative once the window closed.
            counts[1] += jdbc.update("update predictions p set outcome_status = 'KNOWN', outcome_label = true, outcome_value = 1, outcome_at = r.created_at, "
                    + "outcome_note = 'Recurred: ' || r.number from ("
                    + "select p2.id as pid, x.number, x.created_at from predictions p2 join incidents i on i.id = p2.subject_id, "
                    + "lateral (select x.number, x.created_at from incidents x where x.service_id = i.service_id "
                    + "and x.category = i.category and x.parent_id is null and x.id <> i.id and x.created_at > p2.target_at - interval '"
                    + PredictionDatasets.RECURRENCE_DAYS + " days' and x.created_at <= p2.target_at and (p2.simulated or not x.simulated) order by x.created_at limit 1) x "
                    + "where p2.model_type = 'INCIDENT_RECURRENCE' and p2.outcome_status = 'PENDING'"
                    + ") r where p.id = r.pid");
            counts[1] += jdbc.update("update predictions p set outcome_status = 'KNOWN', outcome_label = false, outcome_value = 0, outcome_at = p.target_at, "
                    + "outcome_note = 'No recurrence within " + PredictionDatasets.RECURRENCE_DAYS + " days' where p.model_type = 'INCIDENT_RECURRENCE' "
                    + "and p.outcome_status = 'PENDING' and p.target_at <= ?", Timestamp.from(now));
        });
        List<Map<String, Object>> forecasts = jdbc.queryForList("select id, model_type, subject_id, target, target_at, simulated from predictions "
                + "where outcome_status = 'PENDING' and model_type in ('CAPACITY_RISK', 'SERVICE_HEALTH') and target_at <= ? order by id limit 5000",
                Timestamp.from(now));
        counts[0] = forecasts.size();
        for (Map<String, Object> f : forecasts) {
            long id = ((Number) f.get("id")).longValue();
            Instant target = ((Timestamp) f.get("target_at")).toInstant();
            long subject = ((Number) f.get("subject_id")).longValue();
            if ("CAPACITY_RISK".equals(f.get("model_type"))) {
                if (now.isBefore(target.plus(Duration.ofHours(1)))) {
                    continue;
                }
                Double actual = data.actualHourly(subject, target);
                if (actual != null) {
                    known(id, actual, target.plus(Duration.ofHours(1)), "Hourly average of the target hour");
                    counts[1]++;
                } else if (now.isAfter(target.plus(CAPACITY_EXPIRY))) {
                    expire(id, "No telemetry for the target hour");
                    counts[2]++;
                }
            } else {
                String target0 = (String) f.get("target");
                String metric = target0.contains("@") ? target0.substring(0, target0.indexOf('@')) : target0;
                Instant day = target.minus(Duration.ofDays(1));
                double actual = data.actualDaily(subject, metric, day, Boolean.TRUE.equals(f.get("simulated")));
                known(id, actual, target, "Actual " + metric + " on " + day.toString().substring(0, 10));
                counts[1]++;
            }
        }
        counts[0] += counts[1];
        int drift = 0;
        List<Model> active = models.activeModels();
        for (Model m : active) {
            try {
                Performance p = performance(m, 90);
                String previous = m.driftStatus();
                tx.executeWithoutResult(s -> {
                    models.setDrift(m.id(), p.driftStatus());
                    if ("DRIFT".equals(p.driftStatus()) && !"DRIFT".equals(previous)) {
                        notifications.notifyRoles(List.of(Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.IT_MANAGER), "PREDICTION_DRIFT",
                                "Prediction drift: " + m.type() + " v" + m.version(), String.join("; ", p.driftReasons()), "/predictions", Severity.WARNING);
                    }
                });
                if ("DRIFT".equals(p.driftStatus())) {
                    drift++;
                }
            } catch (RuntimeException e) {
                log.warn("Performance check of prediction model {} failed: {}", m.id(), e.getMessage());
            }
        }
        return new ReconcileResult(now, counts[0], counts[1], counts[2], active.size(), drift);
    }

    private void known(long id, double value, Instant at, String note) {
        jdbc.update("update predictions set outcome_status = 'KNOWN', outcome_value = ?, outcome_at = ?, outcome_note = ? where id = ? and outcome_status = 'PENDING'",
                value, Timestamp.from(at), note, id);
    }

    private void expire(long id, String note) {
        jdbc.update("update predictions set outcome_status = 'EXPIRED', outcome_at = ?, outcome_note = ? where id = ? and outcome_status = 'PENDING'",
                Timestamp.from(clock.instant()), note, id);
    }

    // ------------------------------------------------------------------ performance

    public Performance performance(Long modelId, Integer days) {
        return performance(models.load(modelId), days == null ? 90 : Math.max(1, Math.min(days, 365)));
    }

    @SuppressWarnings("unchecked")
    Performance performance(Model m, int days) {
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofDays(days));
        Instant since = m.activatedAt() != null && m.activatedAt().isAfter(from) ? m.activatedAt() : from;
        Map<String, Object> offline = m.metrics().get("test") instanceof Map<?, ?> t ? (Map<String, Object>) t : m.metrics();
        int minOutcomes = Math.max(1, settings.getInt("predictions.drift.min_outcomes", 30));
        if (m.type() == ModelType.ANOMALY) {
            return anomalyPerformance(m, since, now, offline);
        }
        String sim = m.simulated() ? "" : " and not simulated";
        Map<String, Object> counts = jdbc.queryForMap("select count(*) as total, count(*) filter (where outcome_status = 'PENDING') as pending "
                + "from predictions where model_id = ? and created_at >= ?" + sim, m.id(), Timestamp.from(since));
        List<Map<String, Object>> rows = jdbc.queryForList("select score, lower_bound, upper_bound, outcome_label, outcome_value, created_at from predictions "
                + "where model_id = ? and outcome_status = 'KNOWN' and created_at >= ?" + sim + " order by created_at", m.id(), Timestamp.from(since));
        int total = ((Number) counts.get("total")).intValue();
        int pending = ((Number) counts.get("pending")).intValue();
        boolean classification = m.type() == ModelType.SLA_BREACH || m.type() == ModelType.INCIDENT_RECURRENCE;
        Map<String, Object> live = new LinkedHashMap<>();
        List<Map<String, Object>> overTime = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        String status;
        String message = null;
        if (classification) {
            double[] p = rows.stream().mapToDouble(r -> ((Number) r.get("score")).doubleValue()).toArray();
            int[] y = rows.stream().mapToInt(r -> Boolean.TRUE.equals(r.get("outcome_label")) ? 1 : 0).toArray();
            double threshold = ModelTrainer.num(m.params().get("threshold"), 0.5);
            if (p.length > 0) {
                live.putAll(PredictionMath.classificationMetrics(p, y, threshold));
            } else {
                live.put("samples", 0);
            }
            TreeMap<Instant, List<Map<String, Object>>> weeks = byWeek(rows);
            weeks.forEach((week, list) -> {
                double[] wp = list.stream().mapToDouble(r -> ((Number) r.get("score")).doubleValue()).toArray();
                int[] wy = list.stream().mapToInt(r -> Boolean.TRUE.equals(r.get("outcome_label")) ? 1 : 0).toArray();
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("weekStart", week);
                b.put("outcomes", wp.length);
                b.put("brier", PredictionMath.round4(PredictionMath.brier(wp, wy)));
                b.put("meanPredicted", PredictionMath.round4(java.util.Arrays.stream(wp).average().orElse(0)));
                b.put("observedRate", PredictionMath.round4(java.util.Arrays.stream(wy).average().orElse(0)));
                Double auc = PredictionMath.auc(wp, wy);
                b.put("auc", auc == null ? null : PredictionMath.round4(auc));
                overTime.add(b);
            });
            Double liveAuc = (Double) live.get("auc");
            if (p.length < minOutcomes || liveAuc == null) {
                status = "INSUFFICIENT_DATA";
                message = "Insufficient data — " + p.length + " of " + minOutcomes + " outcomes" + (p.length >= minOutcomes ? " (only one outcome class)" : "");
            } else {
                status = "OK";
                double aucTol = settings.getDouble("predictions.drift.auc_tolerance", 0.10);
                double brierTol = settings.getDouble("predictions.drift.brier_tolerance", 0.05);
                Number offAuc = (Number) offline.get("auc");
                Number offBrier = (Number) offline.get("brier");
                if (offAuc != null && offAuc.doubleValue() - liveAuc > aucTol) {
                    reasons.add("Live AUC " + liveAuc + " is more than " + aucTol + " below the offline AUC " + offAuc);
                }
                double liveBrier = ((Number) live.get("brier")).doubleValue();
                if (offBrier != null && liveBrier - offBrier.doubleValue() > brierTol) {
                    reasons.add("Live Brier score " + liveBrier + " is more than " + brierTol + " above the offline Brier score " + offBrier);
                }
            }
        } else {
            ForecastErrors errors = new ForecastErrors();
            rows.forEach(r -> errors.add(((Number) r.get("outcome_value")).doubleValue(), ((Number) r.get("score")).doubleValue(),
                    num(r.get("lower_bound"), Double.NEGATIVE_INFINITY), num(r.get("upper_bound"), Double.POSITIVE_INFINITY)));
            live.putAll(errors.toMap());
            byWeek(rows).forEach((week, list) -> {
                ForecastErrors e = new ForecastErrors();
                list.forEach(r -> e.add(((Number) r.get("outcome_value")).doubleValue(), ((Number) r.get("score")).doubleValue(),
                        num(r.get("lower_bound"), Double.NEGATIVE_INFINITY), num(r.get("upper_bound"), Double.POSITIVE_INFINITY)));
                Map<String, Object> b = new LinkedHashMap<>(e.toMap());
                b.put("weekStart", week);
                overTime.add(b);
            });
            if (errors.count() < minOutcomes) {
                status = "INSUFFICIENT_DATA";
                message = "Insufficient data — " + errors.count() + " of " + minOutcomes + " outcomes";
            } else {
                status = "OK";
                double ratio = settings.getDouble("predictions.drift.mae_ratio", 1.5);
                double covTol = settings.getDouble("predictions.drift.coverage_tolerance", 0.15);
                Number offMae = (Number) offline.get("mae");
                Number offCov = (Number) offline.get("intervalCoverage");
                if (offMae != null && offMae.doubleValue() > 0 && errors.mae() > offMae.doubleValue() * ratio) {
                    reasons.add("Live MAE " + errors.mae() + " exceeds " + ratio + " × the offline MAE " + offMae);
                }
                if (offCov != null && errors.coverage() < offCov.doubleValue() - covTol) {
                    reasons.add("Live interval coverage " + errors.coverage() + " is more than " + covTol + " below the offline coverage " + offCov);
                }
            }
        }
        if ("OK".equals(status) && !reasons.isEmpty()) {
            status = "DRIFT";
        }
        return new Performance(m.id(), m.type(), m.version(), m.status(), since, now, total, rows.size(), pending, offline, live, overTime, status, reasons,
                message, now);
    }

    @SuppressWarnings("unchecked")
    private Performance anomalyPerformance(Model m, Instant since, Instant now, Map<String, Object> offline) {
        List<JobDto> jobs = models.evaluationsSince(m.id(), since);
        List<Map<String, Object>> overTime = new ArrayList<>();
        for (JobDto j : jobs) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("evaluatedAt", j.finishedAt());
            for (String k : List.of("precision", "recall", "f1", "labels", "detections")) {
                b.put(k, j.result().get(k));
            }
            overTime.add(b);
        }
        Map<String, Object> live = jobs.isEmpty() ? Map.of() : jobs.get(jobs.size() - 1).result();
        List<String> reasons = new ArrayList<>();
        String status;
        String message = null;
        Number liveF1 = (Number) live.get("f1");
        if (liveF1 == null) {
            status = "INSUFFICIENT_DATA";
            message = "Insufficient data — no evaluation with labels since the registration was activated (POST /predictions/models/" + m.id()
                    + "/evaluate)";
        } else {
            status = "OK";
            double tol = settings.getDouble("predictions.drift.f1_tolerance", 0.15);
            Number offF1 = (Number) offline.get("f1");
            if (offF1 != null && offF1.doubleValue() - liveF1.doubleValue() > tol) {
                reasons.add("Latest evaluation F1 " + liveF1 + " is more than " + tol + " below the offline F1 " + offF1);
                status = "DRIFT";
            }
        }
        return new Performance(m.id(), m.type(), m.version(), m.status(), since, now, jobs.size(), jobs.size(), 0, offline, live, overTime, status, reasons,
                message, now);
    }

    private static TreeMap<Instant, List<Map<String, Object>>> byWeek(List<Map<String, Object>> rows) {
        TreeMap<Instant, List<Map<String, Object>>> weeks = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            Instant created = ((Timestamp) r.get("created_at")).toInstant();
            long day = created.truncatedTo(ChronoUnit.DAYS).getEpochSecond() / 86_400;
            // ISO weeks start on Monday; 1970-01-01 was a Thursday.
            Instant week = Instant.ofEpochSecond((day - Math.floorMod(day + 3, 7)) * 86_400);
            weeks.computeIfAbsent(week, k -> new ArrayList<>()).add(r);
        }
        return weeks;
    }

    private static double num(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }
}

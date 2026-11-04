package com.nexus360.prediction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexus360.ai.OpsAiAdvisor;
import com.nexus360.ai.OpsAiDtos.SlaRisk;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.IncidentDtos.SlaSnapshot;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.prediction.PredictionDatasets.DailySeries;
import com.nexus360.prediction.PredictionDatasets.Hourly;
import com.nexus360.prediction.PredictionDtos.CapacityForecast;
import com.nexus360.prediction.PredictionDtos.CapacitySeriesForecast;
import com.nexus360.prediction.PredictionDtos.ClassificationPrediction;
import com.nexus360.prediction.PredictionDtos.Contribution;
import com.nexus360.prediction.PredictionDtos.ForecastPoint;
import com.nexus360.prediction.PredictionDtos.IncidentPredictions;
import com.nexus360.prediction.PredictionDtos.MetricForecast;
import com.nexus360.prediction.PredictionDtos.ModelType;
import com.nexus360.prediction.PredictionDtos.PredictionRecord;
import com.nexus360.prediction.PredictionDtos.ServiceHealthForecast;
import com.nexus360.prediction.PredictionMath.Smoothing;
import com.nexus360.prediction.PredictionModelService.Model;
import com.nexus360.security.Actor;
import com.nexus360.sla.IncidentSla;
import com.nexus360.sla.IncidentSlaRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Serves predictions from the ACTIVE model of each type and records every served model prediction (model version, subject, score,
 * interval, features snapshot) so its outcome can be compared later. Only ACTIVE models serve; without one the response says so — the
 * SLA breach endpoint then answers with the Release 3 statistical SLA risk, labelled {@code STATISTICAL_FALLBACK}. Predictions are
 * statistics ("how this was computed" is part of every response), never presented as AI or LLM output.
 */
@Service
public class PredictionService {

    static final Duration DEDUPE = Duration.ofMinutes(10);
    static final int MAX_CAPACITY_SERIES = 10;

    private final JdbcTemplate jdbc;
    private final PredictionModelService models;
    private final PredictionDatasets data;
    private final IncidentQueryService incidents;
    private final IncidentSlaRepository slas;
    private final IncidentMapper mapper;
    private final OpsAiAdvisor opsAi;
    private final Clock clock;
    private final TransactionTemplate readTx;

    public PredictionService(JdbcTemplate jdbc, PredictionModelService models, PredictionDatasets data, IncidentQueryService incidents,
                             IncidentSlaRepository slas, IncidentMapper mapper, OpsAiAdvisor opsAi, Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.models = models;
        this.data = data;
        this.incidents = incidents;
        this.slas = slas;
        this.mapper = mapper;
        this.opsAi = opsAi;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setReadOnly(true);
    }

    // ------------------------------------------------------------------ incidents

    public IncidentPredictions incident(Long incidentId, Actor actor, boolean record) {
        ClassificationPrediction sla = slaBreach(incidentId, actor, record);
        ClassificationPrediction rec = recurrence(incidentId, actor, record);
        return new IncidentPredictions(incidentId, sla.subjectLabel(), sla, rec);
    }

    /** Probability that the incident's running resolution clock breaches. {@code record=false} serves without storing (agent tools). */
    public ClassificationPrediction slaBreach(Long incidentId, Actor actor, boolean record) {
        incidents.load(incidentId, actor);
        Instant now = clock.instant();
        Optional<Model> active = models.active(ModelType.SLA_BREACH, "");
        boolean includeSimulated = active.map(Model::includeSimulated).orElse(false);
        PredictionDatasets.SlaSubject subject = data.slaSubject(incidentId, includeSimulated);
        if (subject == null) {
            throw ApiException.notFound("Incident", incidentId);
        }
        String label = subject.number() + " · " + subject.title();
        Object[] clockState = readTx.execute(s -> {
            IncidentSla sla = slas.findByIncidentId(incidentId).orElse(null);
            if (sla == null) {
                return null;
            }
            SlaSnapshot snap = mapper.snapshot(sla, now);
            return new Object[]{sla.getOutcome() == null ? null : sla.getOutcome().name(), snap.percentElapsed(), snap.resolutionDueAt()};
        });
        if (clockState == null) {
            return notApplicable(ModelType.SLA_BREACH, "NOT_APPLICABLE", subject, label, "The incident has no resolution SLA clock", now);
        }
        if (clockState[0] != null) {
            return notApplicable(ModelType.SLA_BREACH, "COMPLETE", subject, label, "The resolution clock is complete (" + clockState[0] + "); nothing to predict",
                    now);
        }
        double share = ((Number) clockState[1]).doubleValue() / 100.0;
        if (share >= 1.0) {
            return notApplicable(ModelType.SLA_BREACH, "COMPLETE", subject, label, "The resolution target has already been exceeded (breached)", now);
        }
        if (active.isEmpty()) {
            SlaRisk risk = opsAi.statisticalSlaRisk(incidentId);
            return new ClassificationPrediction(ModelType.SLA_BREACH, "NO_ACTIVE_MODEL", "STATISTICAL_FALLBACK", null, null, "EMPIRICAL_CONDITIONAL_RATE", null,
                    "INCIDENT", incidentId, label, risk.probability(), null, null, risk.riskLevel(), (Instant) clockState[2], Map.of("elapsedShare",
                    PredictionMath.round4(share)), List.of(), Map.of(), "Release 3 statistic (no ACTIVE SLA_BREACH model): " + risk.method(),
                    risk.probabilityLabel(), "No ACTIVE SLA_BREACH model — showing the Release 3 statistical SLA risk instead", subject.simulated(), now, risk);
        }
        Model m = active.get();
        double[] x = PredictionDatasets.slaFeatures(subject.priority(), subject.category(), subject.tier(), subject.teamLoad(), subject.createdAt(), share,
                subject.reassignments());
        return classify(m, x, "INCIDENT", incidentId, label, "breach", (Instant) clockState[2], subject.simulated(), actor, record, now,
                "Breach probability of the running resolution clock at " + Math.round(share * 100) + "% elapsed");
    }

    /** Probability that the same service and category recur within 14 days of the incident's resolution. */
    public ClassificationPrediction recurrence(Long incidentId, Actor actor, boolean record) {
        incidents.load(incidentId, actor);
        Instant now = clock.instant();
        Optional<Model> active = models.active(ModelType.INCIDENT_RECURRENCE, "");
        PredictionDatasets.RecurrenceRow row = data.recurrenceSubject(incidentId, now, active.map(Model::includeSimulated).orElse(false));
        if (row == null) {
            throw ApiException.notFound("Incident", incidentId);
        }
        PredictionDatasets.SlaSubject subjectFacts = new PredictionDatasets.SlaSubject(row.id(), row.number(), row.title(), row.createdAt(), row.priority(),
                null, row.tier(), 0, 0, row.simulated(), row.topLevel(), row.status());
        String label = row.number() + " · " + row.title();
        if (row.serviceId() == null) {
            return notApplicable(ModelType.INCIDENT_RECURRENCE, "NOT_APPLICABLE", subjectFacts, label, "The incident has no business service", now);
        }
        if (row.resolvedAt() == null) {
            return notApplicable(ModelType.INCIDENT_RECURRENCE, "NOT_APPLICABLE", subjectFacts, label,
                    "Recurrence is predicted once the incident is resolved", now);
        }
        Instant target = row.resolvedAt().plus(Duration.ofDays(PredictionDatasets.RECURRENCE_DAYS));
        if (!now.isBefore(target)) {
            return notApplicable(ModelType.INCIDENT_RECURRENCE, "COMPLETE", subjectFacts, label, "The " + PredictionDatasets.RECURRENCE_DAYS
                    + "-day window has closed: the service and category " + (row.recurred() ? "recurred" : "did not recur"), now);
        }
        if (active.isEmpty()) {
            return new ClassificationPrediction(ModelType.INCIDENT_RECURRENCE, "NO_ACTIVE_MODEL", "NONE", null, null, null, null, "INCIDENT", incidentId, label,
                    null, null, null, null, target, Map.of(), List.of(), Map.of(), PredictionModelService.method(ModelType.INCIDENT_RECURRENCE), null,
                    "No ACTIVE INCIDENT_RECURRENCE model — a prediction administrator must train and activate one", row.simulated(), now, null);
        }
        return classify(active.get(), PredictionDatasets.recurrenceFeatures(row), "INCIDENT", incidentId, label, "recurrence", target, row.simulated(), actor,
                record, now, "Probability that " + label + "'s service and category recur within " + PredictionDatasets.RECURRENCE_DAYS + " days of resolution");
    }

    @SuppressWarnings("unchecked")
    private ClassificationPrediction classify(Model m, double[] x, String subjectType, long subjectId, String label, String target, Instant targetAt,
                                              boolean simulated, Actor actor, boolean record, Instant now, String howComputed) {
        double p = ModelTrainer.score(m.params(), x);
        double threshold = ModelTrainer.num(m.params().get("threshold"), 0.5);
        String risk = p >= 0.7 ? "HIGH" : p >= 0.4 ? "MEDIUM" : "LOW";
        List<String> names = (List<String>) m.params().get("featureNames");
        Map<String, Object> features = new LinkedHashMap<>();
        for (int j = 0; j < x.length && j < names.size(); j++) {
            features.put(names.get(j), PredictionMath.round4(x[j]));
        }
        List<Contribution> contributions = ModelTrainer.contributions(m.params(), x);
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("topContributions", contributions.stream().limit(5).toList());
        explanation.put("riskLevel", risk);
        Long predictionId = record ? store(m, subjectType, subjectId, label, target, p, null, null, threshold, risk, targetAt, features, explanation,
                simulated, actor) : null;
        Map<String, Object> test = m.metrics().get("test") instanceof Map<?, ?> t ? (Map<String, Object>) t : Map.of();
        Map<String, Object> offline = new LinkedHashMap<>();
        for (String k : List.of("auc", "brier", "precision", "recall", "threshold", "samples")) {
            offline.put(k, test.get(k));
        }
        return new ClassificationPrediction(m.type(), "OK", "MODEL", m.id(), m.version(), m.algorithm(), predictionId, subjectType, subjectId, label,
                PredictionMath.round4(p), threshold, p >= threshold, risk, targetAt, features, contributions.stream().limit(8).toList(), offline,
                PredictionModelService.method(m.type()), howComputed + ": sigmoid(intercept + Σ weight × standardised feature) with model "
                + m.type() + " v" + m.version() + " (trained " + m.trainedAt() + ")", null, simulated || m.simulated(), now, null);
    }

    private static ClassificationPrediction notApplicable(ModelType type, String status, PredictionDatasets.SlaSubject s, String label, String message,
                                                          Instant now) {
        return new ClassificationPrediction(type, status, "NONE", null, null, null, null, "INCIDENT", s.id(), label, null, null, null, null, null, Map.of(),
                List.of(), Map.of(), PredictionModelService.method(type), null, message, s.simulated(), now, null);
    }

    // ------------------------------------------------------------------ services

    public ServiceHealthForecast serviceHealth(Long serviceId, Integer horizonDays, Actor actor, boolean record) {
        Map<String, Object> svc = jdbc.queryForList("select id, code, name, least(created_at, "
                + "(select min(created_at) from incidents where service_id = business_services.id), "
                + "(select min(first_seen_at) from alerts where service_id = business_services.id)) as created_at "
                + "from business_services where id = ?", serviceId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Service", serviceId));
        int horizon = horizonDays == null ? 7 : horizonDays;
        if (horizon < 1 || horizon > ModelTrainer.MAX_HEALTH_HORIZON) {
            throw ApiException.invalidField("horizonDays", "horizonDays must be between 1 and " + ModelTrainer.MAX_HEALTH_HORIZON);
        }
        Instant now = clock.instant();
        Optional<Model> active = models.active(ModelType.SERVICE_HEALTH, "");
        String code = (String) svc.get("code");
        String name = (String) svc.get("name");
        if (active.isEmpty()) {
            return new ServiceHealthForecast(serviceId, code, name, "NO_ACTIVE_MODEL", "NONE", null, null, null, horizon, List.of(), Map.of(),
                    PredictionModelService.method(ModelType.SERVICE_HEALTH), "No ACTIVE SERVICE_HEALTH model — a prediction administrator must train and "
                    + "activate one", now);
        }
        Model m = active.get();
        double[] abg = {ModelTrainer.num(m.params().get("alpha"), 0.3), ModelTrainer.num(m.params().get("beta"), 0.05),
                ModelTrainer.num(m.params().get("gamma"), 0.1)};
        int historyDays = (int) ModelTrainer.num(m.params().get("historyDays"), 56);
        Instant today = now.truncatedTo(ChronoUnit.DAYS);
        Instant created = ((java.sql.Timestamp) svc.get("created_at")).toInstant().truncatedTo(ChronoUnit.DAYS);
        Instant from = today.minus(Duration.ofDays(historyDays));
        if (created.isAfter(from)) {
            from = created;
        }
        List<MetricForecast> metrics = new ArrayList<>();
        for (DailySeries s : data.serviceHistory(serviceId, from, today, m.includeSimulated())) {
            metrics.add(dailyForecast(m, s, abg, horizon, today, name, record, actor));
        }
        boolean any = metrics.stream().anyMatch(x -> "OK".equals(x.status()));
        return new ServiceHealthForecast(serviceId, code, name, any ? "OK" : "INSUFFICIENT_DATA", "MODEL", m.id(), m.version(), m.algorithm(), horizon,
                metrics, testMetrics(m), PredictionModelService.method(m.type()) + "; fitted per metric on the last " + historyDays
                + " complete UTC days with alpha=" + abg[0] + ", beta=" + abg[1] + ", gamma=" + abg[2] + " from model v" + m.version(),
                any ? null : "Insufficient data — fewer than " + PredictionDatasets.SEASON + " days of history", now);
    }

    private MetricForecast dailyForecast(Model m, DailySeries s, double[] abg, int horizon, Instant today, String serviceName, boolean record, Actor actor) {
        double[] y = s.values();
        List<ForecastPoint> history = new ArrayList<>();
        for (int i = Math.max(0, y.length - 28); i < y.length; i++) {
            history.add(new ForecastPoint(s.start().plus(Duration.ofDays(i)), y[i], y[i], y[i]));
        }
        if (y.length < PredictionDatasets.SEASON) {
            return new MetricForecast(s.metric(), "INSUFFICIENT_DATA", null, null, y.length, history, List.of(), null,
                    "Insufficient data — " + y.length + " of " + PredictionDatasets.SEASON + " days of history");
        }
        Smoothing fit = ModelTrainer.fitDaily(y, abg);
        boolean rate = s.metric().startsWith("errorRate");
        List<ForecastPoint> forecast = new ArrayList<>();
        for (int h = 1; h <= horizon; h++) {
            double f = Math.max(0, fit.forecast(h, y.length - 1));
            double sd = fit.sd(h);
            double upper = f + PredictionMath.Z95 * sd;
            forecast.add(new ForecastPoint(today.plus(Duration.ofDays(h - 1L)), PredictionMath.round4(f), PredictionMath.round4(Math.max(0, f - PredictionMath.Z95 * sd)),
                    PredictionMath.round4(rate ? Math.min(100, upper) : upper)));
        }
        Long predictionId = null;
        if (record && !rate) {
            ForecastPoint first = forecast.get(0);
            Map<String, Object> features = new LinkedHashMap<>();
            features.put("metric", s.metric());
            features.put("day", first.at().toString());
            features.put("historyDays", y.length);
            features.put("lastValues", history.stream().skip(Math.max(0, history.size() - 7)).map(ForecastPoint::predicted).toList());
            Map<String, Object> explanation = Map.of("method", fit.method(), "sigma", PredictionMath.round4(fit.sigma()));
            predictionId = store(m, "SERVICE", s.serviceId(), serviceName, s.metric() + "@" + first.at().toString().substring(0, 10), first.predicted(),
                    first.lower(), first.upper(), null, null, first.at().plus(Duration.ofDays(1)), features, explanation, false, actor);
        }
        return new MetricForecast(s.metric(), "OK", fit.method(), predictionId, y.length, history, forecast, PredictionMath.round4(fit.sigma()), null);
    }

    // ------------------------------------------------------------------ capacity

    public CapacityForecast capacity(Long seriesId, Long ciId, Long serviceId, String metric, Double threshold, Integer horizonHours, Actor actor,
                                     boolean record) {
        if (seriesId == null && ciId == null && serviceId == null) {
            throw ApiException.badRequest("Give seriesId, ciId or serviceId");
        }
        int horizon = horizonHours == null ? 72 : horizonHours;
        if (horizon < 1 || horizon > 720) {
            throw ApiException.invalidField("horizonHours", "horizonHours must be between 1 and 720");
        }
        if (threshold != null && (threshold.isNaN() || threshold.isInfinite())) {
            throw ApiException.invalidField("threshold", "threshold must be a finite number");
        }
        Instant now = clock.instant();
        Optional<Model> active = models.active(ModelType.CAPACITY_RISK, "");
        if (active.isEmpty()) {
            return new CapacityForecast("NO_ACTIVE_MODEL", "NONE", null, null, null, horizon, List.of(), Map.of(),
                    PredictionModelService.method(ModelType.CAPACITY_RISK), "No ACTIVE CAPACITY_RISK model — a prediction administrator must train and "
                    + "activate one", now);
        }
        Model m = active.get();
        double a = ModelTrainer.num(m.params().get("alpha"), 0.5);
        double b = ModelTrainer.num(m.params().get("beta"), 0.1);
        int lookback = (int) ModelTrainer.num(m.params().get("lookbackHours"), 336);
        Instant end = now.truncatedTo(ChronoUnit.HOURS);
        Instant from = end.minus(Duration.ofHours(lookback));
        List<PredictionDatasets.SeriesInfo> series = data.capacitySeries(from, now.plusSeconds(1), m.includeSimulated(), seriesId, serviceId, ciId, metric);
        if (series.size() > MAX_CAPACITY_SERIES) {
            series = series.subList(0, MAX_CAPACITY_SERIES);
        }
        List<CapacitySeriesForecast> out = new ArrayList<>();
        for (Hourly h : data.hourly(series, from, now.plusSeconds(1))) {
            out.add(capacitySeries(m, h, a, b, threshold, horizon, from, record, actor));
        }
        String status = out.stream().anyMatch(s -> "OK".equals(s.status())) ? "OK" : out.isEmpty() ? "NO_DATA" : "INSUFFICIENT_DATA";
        return new CapacityForecast(status, "MODEL", m.id(), m.version(), m.algorithm(), horizon, out, testMetrics(m),
                PredictionModelService.method(m.type()) + "; Holt fitted on the last " + lookback + " hours with alpha=" + a + ", beta=" + b + " from model v"
                        + m.version(), out.isEmpty() ? "No utilisation series in the last " + lookback + " hours" : null, now);
    }

    private CapacitySeriesForecast capacitySeries(Model m, Hourly h, double a, double b, Double requested, int horizon, Instant from, boolean record,
                                                  Actor actor) {
        PredictionDatasets.SeriesInfo s = h.series();
        Map<String, String> attributes = models.read(s.attributes(), new TypeReference<Map<String, String>>() { });
        double[] y = h.values();
        Double current = y.length == 0 ? null : PredictionMath.round4(y[y.length - 1]);
        if (h.observed() < ModelTrainer.MIN_HOURLY_TRAIN) {
            return new CapacitySeriesForecast(s.id(), s.name(), attributes, s.resourceId(), s.resourceServiceName(), s.serviceId(), s.ciId(), "INSUFFICIENT_DATA",
                    null, null, requested == null ? 0 : requested, null, current, null, null, null, false, null, h.observed(), List.of(),
                    "Insufficient data — " + h.observed() + " of " + ModelTrainer.MIN_HOURLY_TRAIN + " hourly points", s.simulated());
        }
        double threshold;
        String basis;
        if (requested != null) {
            threshold = requested;
            basis = "REQUESTED";
        } else {
            Double limit = data.limitFor(s, from);
            Double max = data.maxValue(s.id(), from);
            String name = s.name().toLowerCase(Locale.ROOT);
            if (limit != null && limit > 0) {
                threshold = 0.9 * limit;
                basis = "90% of the limit series (" + PredictionMath.round4(limit) + ")";
            } else if (max != null && max <= 1.0) {
                threshold = 0.9;
                basis = "90% utilisation (ratio metric)";
            } else if (max != null && max <= 100 && name.matches(".*(utiliz|utilis|percent|pct).*")) {
                threshold = 90;
                basis = "90% utilisation (percent metric)";
            } else {
                return new CapacitySeriesForecast(s.id(), s.name(), attributes, s.resourceId(), s.resourceServiceName(), s.serviceId(), s.ciId(),
                        "NO_THRESHOLD", null, null, 0, null, current, null, null, null, false, null, h.observed(), List.of(),
                        "No default threshold for " + s.name() + " (not a ratio or percent metric and no limit series); pass threshold", s.simulated());
            }
        }
        Smoothing fit = PredictionMath.holt(y, a, b);
        List<ForecastPoint> forecast = new ArrayList<>();
        Double pointCross = null;
        Double upperCross = null;
        Double lowerCross = null;
        if (current != null && current >= threshold) {
            pointCross = 0.0;
            upperCross = 0.0;
            lowerCross = 0.0;
        }
        Instant last = h.start().plus(Duration.ofHours(y.length - 1L));
        for (int k = 1; k <= horizon; k++) {
            double f = fit.forecast(k, y.length - 1);
            double sd = fit.sd(k);
            double lo = f - PredictionMath.Z95 * sd;
            double hi = f + PredictionMath.Z95 * sd;
            forecast.add(new ForecastPoint(last.plus(Duration.ofHours(k)), PredictionMath.round4(f), PredictionMath.round4(lo), PredictionMath.round4(hi)));
            if (pointCross == null && f >= threshold) {
                pointCross = (double) k;
            }
            if (upperCross == null && hi >= threshold) {
                upperCross = (double) k;
            }
            if (lowerCross == null && lo >= threshold) {
                lowerCross = (double) k;
            }
        }
        boolean atRisk = upperCross != null;
        String risk = pointCross != null ? "HIGH" : atRisk ? "MEDIUM" : "LOW";
        Long predictionId = null;
        if (record) {
            int check = Math.min(horizon, 24);
            ForecastPoint p = forecast.get(check - 1);
            Map<String, Object> features = new LinkedHashMap<>();
            features.put("seriesId", s.id());
            features.put("metric", s.name());
            features.put("historyPoints", h.observed());
            features.put("lastValue", current);
            features.put("level", PredictionMath.round4(fit.level()));
            features.put("trendPerHour", PredictionMath.round4(fit.trend()));
            Map<String, Object> explanation = new LinkedHashMap<>();
            explanation.put("hoursToThreshold", pointCross);
            explanation.put("earliestHours", upperCross);
            explanation.put("latestHours", lowerCross);
            explanation.put("thresholdBasis", basis);
            explanation.put("checkHours", check);
            predictionId = store(m, "SERIES", s.id(), s.name(), "value@" + check + "h", p.predicted(), p.lower(), p.upper(), threshold, risk, p.at(), features,
                    explanation, s.simulated(), actor);
        }
        return new CapacitySeriesForecast(s.id(), s.name(), attributes, s.resourceId(), s.resourceServiceName(), s.serviceId(), s.ciId(), "OK", fit.method(),
                predictionId, PredictionMath.round4(threshold), basis, current, pointCross, upperCross, lowerCross, atRisk, risk, h.observed(), forecast, null,
                s.simulated());
    }

    // ------------------------------------------------------------------ stored predictions

    public PageResponse<PredictionRecord> list(Long modelId, String subjectType, Long subjectId, String outcome, Integer page, Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int n = size == null ? 20 : Math.max(1, Math.min(size, 100));
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (modelId != null) {
            where.append(" and model_id = ?");
            args.add(modelId);
        }
        if (subjectType != null && !subjectType.isBlank()) {
            String t = subjectType.trim().toUpperCase(Locale.ROOT);
            if (!List.of("INCIDENT", "SERVICE", "SERIES", "DETECTOR").contains(t)) {
                throw ApiException.invalidField("subjectType", "subjectType must be INCIDENT, SERVICE, SERIES or DETECTOR");
            }
            where.append(" and subject_type = ?");
            args.add(t);
        }
        if (subjectId != null) {
            where.append(" and subject_id = ?");
            args.add(subjectId);
        }
        if (outcome != null && !outcome.isBlank()) {
            String o = outcome.trim().toUpperCase(Locale.ROOT);
            if (!List.of("PENDING", "KNOWN", "EXPIRED").contains(o)) {
                throw ApiException.invalidField("outcome", "outcome must be PENDING, KNOWN or EXPIRED");
            }
            where.append(" and outcome_status = ?");
            args.add(o);
        }
        Long total = jdbc.queryForObject("select count(*) from predictions" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(n);
        pageArgs.add((long) p * n);
        List<PredictionRecord> content = jdbc.query("select * from predictions" + where + " order by created_at desc, id desc limit ? offset ?",
                (rs, i) -> record(rs), pageArgs.toArray());
        return new PageResponse<>(content, p, n, total == null ? 0 : total, (int) Math.ceil((total == null ? 0 : total) / (double) n));
    }

    PredictionRecord record(ResultSet rs) throws SQLException {
        return new PredictionRecord(rs.getLong("id"), rs.getLong("model_id"), ModelType.valueOf(rs.getString("model_type")), rs.getInt("model_version"),
                rs.getString("subject_type"), rs.getLong("subject_id"), rs.getString("subject_label"), rs.getString("target"), rs.getDouble("score"),
                (Double) rs.getObject("lower_bound"), (Double) rs.getObject("upper_bound"), (Double) rs.getObject("threshold"), rs.getString("risk_level"),
                PredictionModelService.inst(rs, "target_at"), models.read(rs.getString("features"), new TypeReference<Map<String, Object>>() { }),
                models.read(rs.getString("explanation"), new TypeReference<Map<String, Object>>() { }), rs.getString("outcome_status"),
                (Boolean) rs.getObject("outcome_label"), (Double) rs.getObject("outcome_value"), PredictionModelService.inst(rs, "outcome_at"),
                rs.getString("outcome_note"), rs.getBoolean("simulated"), PredictionModelService.inst(rs, "created_at"));
    }

    /** Stores a served prediction; an identical prediction of the same model, subject and target within 10 minutes is reused. */
    private Long store(Model m, String subjectType, long subjectId, String label, String target, double score, Double lower, Double upper, Double threshold,
                       String risk, Instant targetAt, Map<String, Object> features, Map<String, Object> explanation, boolean simulated, Actor actor) {
        Instant now = clock.instant();
        List<Map<String, Object>> recent = jdbc.queryForList("select id, score from predictions where model_id = ? and subject_type = ? and subject_id = ? "
                + "and target = ? and created_at >= ? order by created_at desc limit 1", m.id(), subjectType, subjectId, target,
                java.sql.Timestamp.from(now.minus(DEDUPE)));
        double rounded = PredictionMath.round4(score);
        if (!recent.isEmpty() && Math.abs(((Number) recent.get(0).get("score")).doubleValue() - rounded) < 1e-9) {
            return ((Number) recent.get(0).get("id")).longValue();
        }
        return jdbc.queryForObject("insert into predictions (model_id, model_type, model_version, subject_type, subject_id, subject_label, target, score, "
                + "lower_bound, upper_bound, threshold, risk_level, target_at, features, explanation, simulated, requested_by_id, created_at) values "
                + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?) returning id", Long.class, m.id(), m.type().name(), m.version(),
                subjectType, subjectId, PredictionModelService.truncate(label, 250), target, rounded, lower, upper, threshold, risk,
                PredictionModelService.ts(targetAt), models.write(features), models.write(explanation), simulated || m.simulated(), actor.id(),
                java.sql.Timestamp.from(now));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> testMetrics(Model m) {
        return m.metrics().get("test") instanceof Map<?, ?> t ? (Map<String, Object>) t : Map.of();
    }
}

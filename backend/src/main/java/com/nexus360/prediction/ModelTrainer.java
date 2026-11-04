package com.nexus360.prediction;

import com.nexus360.admin.SettingsService;
import com.nexus360.observability.AnomalyService;
import com.nexus360.observability.ObservabilityDtos;
import com.nexus360.prediction.PredictionDatasets.DailySeries;
import com.nexus360.prediction.PredictionDatasets.Hourly;
import com.nexus360.prediction.PredictionDatasets.Sample;
import com.nexus360.prediction.PredictionDtos.ModelStatus;
import com.nexus360.prediction.PredictionDtos.ModelType;
import com.nexus360.prediction.PredictionMath.ForecastErrors;
import com.nexus360.prediction.PredictionMath.Logistic;
import com.nexus360.prediction.PredictionMath.Smoothing;
import com.nexus360.prediction.PredictionMath.Standardizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Fits and evaluates the prediction models. Every type uses a <b>time-based split</b>: the oldest {@code 1 − testFraction} of the
 * window trains the model, the most recent {@code testFraction} is held out for the offline evaluation. Minimum sample sizes are
 * enforced before anything is fitted; below them the outcome is {@code INSUFFICIENT_DATA} with "Insufficient data — N of M …".
 */
@Component
@RequiredArgsConstructor
public class ModelTrainer {

    static final double DEFAULT_L2 = 1.0;
    static final double DEFAULT_TEST_FRACTION = 0.25;
    static final int MIN_HOURLY_TRAIN = 24;
    static final int MIN_HOURLY_TEST = 6;
    static final int MAX_CAPACITY_HORIZON = 168;
    static final int MIN_DAILY_TRAIN = 14;
    static final int MIN_DAILY_TEST = 3;
    static final int MAX_HEALTH_HORIZON = 28;

    private final PredictionDatasets data;
    private final SettingsService settings;
    private final AnomalyService anomalies;
    private final Clock clock;

    /** What a training (or evaluation) produced; persisted as a model version. */
    public record Outcome(ModelStatus status, String message, String algorithm, List<String> features, Map<String, Object> params,
                          Map<String, Object> metrics, Instant cutoff, int samples, Integer positives, int train, int test, int minSamples) {}

    public record Window(Instant from, Instant to) {}

    int minSamples() {
        return Math.max(10, settings.getInt("predictions.min_samples", 200));
    }

    int minPositives() {
        return Math.max(2, settings.getInt("predictions.min_positives", 30));
    }

    int minForecastPoints() {
        return Math.max(10, settings.getInt("predictions.min_forecast_points", 100));
    }

    // ------------------------------------------------------------------ classification (SLA_BREACH, INCIDENT_RECURRENCE)

    public Outcome trainSla(Window w, boolean includeSimulated, double testFraction, double l2) {
        List<PredictionDatasets.SlaIncident> incidents = data.slaIncidents(w.from(), w.to(), includeSimulated);
        int positives = (int) incidents.stream().filter(PredictionDatasets.SlaIncident::breached).count();
        Outcome gate = classificationGate(incidents.size(), positives, "incidents with a completed resolution clock", "breached");
        if (gate != null) {
            return gate;
        }
        List<Sample> samples = PredictionDatasets.slaSamples(incidents);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("observationShares", Arrays.stream(PredictionDatasets.OBSERVATION_SHARES).boxed().toList());
        return fitClassifier("LOGISTIC_REGRESSION_L2", PredictionDatasets.SLA_FEATURES, samples, incidents.size(), positives, testFraction, l2, extra);
    }

    public Outcome trainRecurrence(Window w, boolean includeSimulated, double testFraction, double l2) {
        List<PredictionDatasets.RecurrenceRow> rows = data.recurrenceRows(w.from(), w.to(), clock.instant(), includeSimulated);
        int positives = (int) rows.stream().filter(PredictionDatasets.RecurrenceRow::recurred).count();
        Outcome gate = classificationGate(rows.size(), positives, "resolved incidents with a closed " + PredictionDatasets.RECURRENCE_DAYS
                + "-day window", "recurred");
        if (gate != null) {
            return gate;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("recurrenceDays", PredictionDatasets.RECURRENCE_DAYS);
        return fitClassifier("LOGISTIC_REGRESSION_L2", PredictionDatasets.RECURRENCE_FEATURES, PredictionDatasets.recurrenceSamples(rows), rows.size(),
                positives, testFraction, l2, extra);
    }

    private Outcome classificationGate(int units, int positives, String unitLabel, String positiveLabel) {
        int min = minSamples();
        int minPos = minPositives();
        int negatives = units - positives;
        if (units < min || positives < minPos || negatives < minPos) {
            String msg = "Insufficient data — " + units + " of " + min + " " + unitLabel + " (" + positives + " " + positiveLabel + ", " + negatives
                    + " not; at least " + minPos + " of each are required)";
            return new Outcome(ModelStatus.INSUFFICIENT_DATA, msg, "LOGISTIC_REGRESSION_L2", List.of(), Map.of(),
                    Map.of("samples", units, "positives", positives, "negatives", negatives, "minSamples", min, "minPositives", minPos), null, units,
                    positives, 0, 0, min);
        }
        return null;
    }

    /** Splits by subject time (all samples of one subject fall on the same side), standardises, fits and evaluates. */
    Outcome fitClassifier(String algorithm, List<String> features, List<Sample> samples, int units, int positives, double testFraction, double l2,
                          Map<String, Object> extra) {
        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparing(Sample::at).thenComparingLong(Sample::subjectId));
        List<Long> subjects = sorted.stream().map(Sample::subjectId).distinct().toList();
        int cut = (int) Math.round(subjects.size() * (1 - testFraction));
        cut = Math.max(1, Math.min(subjects.size() - 1, cut));
        java.util.Set<Long> trainSubjects = new java.util.HashSet<>(subjects.subList(0, cut));
        List<Sample> train = sorted.stream().filter(s -> trainSubjects.contains(s.subjectId())).toList();
        List<Sample> test = sorted.stream().filter(s -> !trainSubjects.contains(s.subjectId())).toList();
        Instant cutoff = test.isEmpty() ? null : test.get(0).at();
        int[] yTrain = train.stream().mapToInt(Sample::y).toArray();
        int[] yTest = test.stream().mapToInt(Sample::y).toArray();
        if (Arrays.stream(yTrain).distinct().count() < 2 || Arrays.stream(yTest).distinct().count() < 2) {
            String msg = "Insufficient data — the " + (Arrays.stream(yTrain).distinct().count() < 2 ? "training" : "test")
                    + " part of the time split contains only one outcome class; widen the window";
            return new Outcome(ModelStatus.INSUFFICIENT_DATA, msg, algorithm, features, Map.of(), Map.of("samples", units, "positives", positives), cutoff,
                    units, positives, train.size(), test.size(), minSamples());
        }
        double[][] xTrain = train.stream().map(Sample::x).toArray(double[][]::new);
        Standardizer standardizer = Standardizer.fit(xTrain);
        Logistic fit = PredictionMath.fitLogistic(standardizer.apply(xTrain), yTrain, l2, 50);
        double[] pTrain = Arrays.stream(standardizer.apply(xTrain)).mapToDouble(fit::probability).toArray();
        double threshold = PredictionMath.bestF1Threshold(pTrain, yTrain);
        double[] pTest = test.stream().mapToDouble(s -> fit.probability(standardizer.apply(s.x()))).toArray();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("featureNames", features);
        params.put("means", round(standardizer.means()));
        params.put("stds", round(standardizer.stds()));
        params.put("intercept", fit.weights()[0]);
        params.put("weights", Arrays.stream(fit.weights()).skip(1).boxed().toList());
        params.put("l2", l2);
        params.put("threshold", PredictionMath.round4(threshold));
        params.put("iterations", fit.iterations());
        params.put("converged", fit.converged());
        params.putAll(extra);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("evaluation", "TIME_SPLIT");
        metrics.put("testFraction", testFraction);
        metrics.put("test", PredictionMath.classificationMetrics(pTest, yTest, threshold));
        Map<String, Object> trainMetrics = new LinkedHashMap<>();
        Double trainAuc = PredictionMath.auc(pTrain, yTrain);
        trainMetrics.put("samples", train.size());
        trainMetrics.put("auc", trainAuc == null ? null : PredictionMath.round4(trainAuc));
        trainMetrics.put("brier", PredictionMath.round4(PredictionMath.brier(pTrain, yTrain)));
        metrics.put("train", trainMetrics);
        metrics.put("units", Map.of("subjects", units, "positives", positives, "negatives", units - positives));
        String msg = "Trained on " + train.size() + " samples (" + cut + " subjects), evaluated on " + test.size() + " later samples";
        return new Outcome(ModelStatus.TRAINED, msg, algorithm, features, params, metrics, cutoff, units, positives, train.size(), test.size(), minSamples());
    }

    /** Re-scores a stored classifier on samples of a new window (out-of-time evaluation; no refit). */
    public Map<String, Object> evaluateClassifier(Map<String, Object> params, List<Sample> samples) {
        if (samples.isEmpty()) {
            return Map.of("status", "INSUFFICIENT_DATA", "message", "Insufficient data — 0 samples in the evaluation window");
        }
        double[] p = samples.stream().mapToDouble(s -> score(params, s.x())).toArray();
        int[] y = samples.stream().mapToInt(Sample::y).toArray();
        Map<String, Object> m = new LinkedHashMap<>(PredictionMath.classificationMetrics(p, y, num(params.get("threshold"), 0.5)));
        m.put("status", m.get("auc") == null ? "INSUFFICIENT_DATA" : "EVALUATED");
        if (m.get("auc") == null) {
            m.put("message", "Insufficient data — the evaluation window contains only one outcome class");
        }
        return m;
    }

    /** Probability of a stored logistic model for raw feature vector {@code x}. */
    @SuppressWarnings("unchecked")
    public static double score(Map<String, Object> params, double[] x) {
        double[] means = doubles((List<Number>) params.get("means"));
        double[] stds = doubles((List<Number>) params.get("stds"));
        double[] w = doubles((List<Number>) params.get("weights"));
        double s = num(params.get("intercept"), 0);
        for (int j = 0; j < x.length && j < w.length; j++) {
            s += w[j] * (x[j] - means[j]) / (stds[j] == 0 ? 1 : stds[j]);
        }
        return PredictionMath.sigmoid(s);
    }

    @SuppressWarnings("unchecked")
    public static List<PredictionDtos.Contribution> contributions(Map<String, Object> params, double[] x) {
        double[] means = doubles((List<Number>) params.get("means"));
        double[] stds = doubles((List<Number>) params.get("stds"));
        double[] w = doubles((List<Number>) params.get("weights"));
        List<String> names = (List<String>) params.get("featureNames");
        List<PredictionDtos.Contribution> out = new ArrayList<>();
        for (int j = 0; j < x.length && j < w.length; j++) {
            double z = (x[j] - means[j]) / (stds[j] == 0 ? 1 : stds[j]);
            out.add(new PredictionDtos.Contribution(names.get(j), PredictionMath.round4(x[j]), PredictionMath.round4(z), PredictionMath.round4(w[j]),
                    PredictionMath.round4(w[j] * z)));
        }
        out.sort(Comparator.comparingDouble((PredictionDtos.Contribution c) -> -Math.abs(c.contribution())));
        return out;
    }

    // ------------------------------------------------------------------ CAPACITY_RISK

    public Outcome trainCapacity(Window w, boolean includeSimulated, double testFraction) {
        List<Hourly> series = data.hourly(data.capacitySeries(w.from(), w.to(), includeSimulated, null, null, null, null), w.from(), w.to());
        int points = series.stream().mapToInt(Hourly::observed).sum();
        Instant cutoff = w.from().plusSeconds((long) (Duration.between(w.from(), w.to()).toSeconds() * (1 - testFraction)));
        List<double[]> trainParts = new ArrayList<>();
        List<double[][]> pairs = new ArrayList<>();
        for (Hourly h : series) {
            int split = (int) Math.max(0, Math.min(h.values().length, Duration.between(h.start(), cutoff).toHours()));
            if (split >= MIN_HOURLY_TRAIN && h.values().length - split >= MIN_HOURLY_TEST) {
                double[] train = Arrays.copyOfRange(h.values(), 0, split);
                trainParts.add(train);
                pairs.add(new double[][]{train, Arrays.copyOfRange(h.values(), split, Math.min(h.values().length, split + MAX_CAPACITY_HORIZON))});
            }
        }
        int min = minForecastPoints();
        if (points < min || pairs.isEmpty()) {
            String msg = "Insufficient data — " + points + " of " + min + " hourly utilisation points" + (pairs.isEmpty() && points >= min
                    ? " (no series has " + MIN_HOURLY_TRAIN + " training hours before and " + MIN_HOURLY_TEST + " test hours after the cutoff)" : "");
            return new Outcome(ModelStatus.INSUFFICIENT_DATA, msg, "HOLT_LINEAR_TREND", List.of(), Map.of(),
                    Map.of("points", points, "series", series.size(), "minPoints", min), cutoff, points, null, 0, 0, min);
        }
        double[] ab = PredictionMath.bestHolt(trainParts);
        ForecastErrors errors = new ForecastErrors();
        int trainPoints = 0;
        for (double[][] pair : pairs) {
            Smoothing fit = PredictionMath.holt(pair[0], ab[0], ab[1]);
            trainPoints += pair[0].length;
            for (int h = 1; h <= pair[1].length; h++) {
                double f = fit.forecast(h, pair[0].length - 1);
                double sd = fit.sd(h);
                errors.add(pair[1][h - 1], f, f - PredictionMath.Z95 * sd, f + PredictionMath.Z95 * sd);
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("alpha", ab[0]);
        params.put("beta", ab[1]);
        params.put("interval", "95% normal, h-step sd = sigma * sqrt(1 + sum_{j<h} (alpha (1 + j beta))^2)");
        params.put("lookbackHours", 336);
        params.put("metricPattern", PredictionDatasets.CAPACITY_PATTERN);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("evaluation", "TIME_SPLIT");
        metrics.put("testFraction", testFraction);
        metrics.put("test", errors.toMap());
        metrics.put("series", pairs.size());
        metrics.put("seriesInWindow", series.size());
        return new Outcome(ModelStatus.TRAINED, "Grid-searched alpha/beta on " + pairs.size() + " series, evaluated on " + errors.count()
                + " later hourly points", "HOLT_LINEAR_TREND", List.of("hourly average of the series"), params, metrics, cutoff, points, null, trainPoints,
                errors.count(), min);
    }

    public Map<String, Object> evaluateCapacity(Map<String, Object> params, Window w, boolean includeSimulated) {
        List<Hourly> series = data.hourly(data.capacitySeries(w.from(), w.to(), includeSimulated, null, null, null, null), w.from(), w.to());
        ForecastErrors errors = new ForecastErrors();
        double a = num(params.get("alpha"), 0.5);
        double b = num(params.get("beta"), 0.1);
        for (Hourly h : series) {
            int split = (int) Math.round(h.values().length * (1 - DEFAULT_TEST_FRACTION));
            if (split >= MIN_HOURLY_TRAIN && h.values().length - split >= 1) {
                double[] train = Arrays.copyOfRange(h.values(), 0, split);
                Smoothing fit = PredictionMath.holt(train, a, b);
                for (int k = split; k < Math.min(h.values().length, split + MAX_CAPACITY_HORIZON); k++) {
                    int step = k - split + 1;
                    double f = fit.forecast(step, split - 1);
                    double sd = fit.sd(step);
                    errors.add(h.values()[k], f, f - PredictionMath.Z95 * sd, f + PredictionMath.Z95 * sd);
                }
            }
        }
        return forecastEvaluation(errors);
    }

    // ------------------------------------------------------------------ SERVICE_HEALTH

    public Outcome trainServiceHealth(Window w, boolean includeSimulated, double testFraction) {
        List<DailySeries> series = data.dailyCounts(w.from(), w.to(), includeSimulated, null).stream()
                .filter(s -> Arrays.stream(s.values()).sum() >= 5).toList();
        int points = series.stream().mapToInt(s -> s.values().length).sum();
        Instant cutoff = w.from().plusSeconds((long) (Duration.between(w.from(), w.to()).toSeconds() * (1 - testFraction)))
                .truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        List<double[][]> pairs = new ArrayList<>();
        for (DailySeries s : series) {
            int split = (int) Math.max(0, Math.min(s.values().length, Duration.between(s.start(), cutoff).toDays()));
            if (split >= MIN_DAILY_TRAIN && s.values().length - split >= MIN_DAILY_TEST) {
                pairs.add(new double[][]{Arrays.copyOfRange(s.values(), 0, split),
                        Arrays.copyOfRange(s.values(), split, Math.min(s.values().length, split + MAX_HEALTH_HORIZON))});
            }
        }
        int min = minForecastPoints();
        if (points < min || pairs.isEmpty()) {
            String msg = "Insufficient data — " + points + " of " + min + " daily service points" + (pairs.isEmpty() && points >= min
                    ? " (no service has " + MIN_DAILY_TRAIN + " training days before and " + MIN_DAILY_TEST + " test days after the cutoff)" : "");
            return new Outcome(ModelStatus.INSUFFICIENT_DATA, msg, "HOLT_WINTERS_ADDITIVE", List.of(), Map.of(),
                    Map.of("points", points, "series", series.size(), "minPoints", min), cutoff, points, null, 0, 0, min);
        }
        int m = PredictionDatasets.SEASON;
        double[] abg = PredictionMath.bestHoltWinters(pairs.stream().map(p -> p[0]).toList(), m);
        ForecastErrors errors = new ForecastErrors();
        int trainPoints = 0;
        Map<String, Integer> methods = new LinkedHashMap<>();
        for (double[][] pair : pairs) {
            Smoothing fit = fitDaily(pair[0], abg);
            methods.merge(fit.method(), 1, Integer::sum);
            trainPoints += pair[0].length;
            for (int h = 1; h <= pair[1].length; h++) {
                double f = Math.max(0, fit.forecast(h, pair[0].length - 1));
                double sd = fit.sd(h);
                errors.add(pair[1][h - 1], f, Math.max(0, f - PredictionMath.Z95 * sd), f + PredictionMath.Z95 * sd);
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("alpha", abg[0]);
        params.put("beta", abg[1]);
        params.put("gamma", abg[2]);
        params.put("season", m);
        params.put("historyDays", 56);
        params.put("fallback", "SEASONAL_NAIVE below two seasons (" + 2 * m + " days)");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("evaluation", "TIME_SPLIT");
        metrics.put("testFraction", testFraction);
        metrics.put("test", errors.toMap());
        metrics.put("series", pairs.size());
        metrics.put("methods", methods);
        return new Outcome(ModelStatus.TRAINED, "Grid-searched alpha/beta/gamma on " + pairs.size() + " service series, evaluated on " + errors.count()
                + " later days", "HOLT_WINTERS_ADDITIVE", List.of("daily incident count", "daily alert count"), params, metrics, cutoff, points, null,
                trainPoints, errors.count(), min);
    }

    /** Holt–Winters with two or more seasons of history, seasonal naive with one season; null below one season. */
    static Smoothing fitDaily(double[] y, double[] abg) {
        int m = PredictionDatasets.SEASON;
        if (y.length >= 2 * m) {
            return PredictionMath.holtWinters(y, m, abg[0], abg[1], abg[2]);
        }
        return y.length >= m ? PredictionMath.seasonalNaive(y, m) : null;
    }

    public Map<String, Object> evaluateServiceHealth(Map<String, Object> params, Window w, boolean includeSimulated) {
        double[] abg = {num(params.get("alpha"), 0.3), num(params.get("beta"), 0.05), num(params.get("gamma"), 0.1)};
        ForecastErrors errors = new ForecastErrors();
        for (DailySeries s : data.dailyCounts(w.from(), w.to(), includeSimulated, null)) {
            int split = (int) Math.round(s.values().length * (1 - DEFAULT_TEST_FRACTION));
            if (split >= PredictionDatasets.SEASON && s.values().length - split >= 1) {
                Smoothing fit = fitDaily(Arrays.copyOfRange(s.values(), 0, split), abg);
                for (int k = split; k < Math.min(s.values().length, split + MAX_HEALTH_HORIZON); k++) {
                    int step = k - split + 1;
                    double f = Math.max(0, fit.forecast(step, split - 1));
                    double sd = fit.sd(step);
                    errors.add(s.values()[k], f, Math.max(0, f - PredictionMath.Z95 * sd), f + PredictionMath.Z95 * sd);
                }
            }
        }
        return forecastEvaluation(errors);
    }

    private static Map<String, Object> forecastEvaluation(ForecastErrors errors) {
        Map<String, Object> m = new LinkedHashMap<>(errors.toMap());
        m.put("status", errors.count() == 0 ? "INSUFFICIENT_DATA" : "EVALUATED");
        if (errors.count() == 0) {
            m.put("message", "Insufficient data — no series with enough history in the evaluation window");
        }
        return m;
    }

    // ------------------------------------------------------------------ ANOMALY (observability detectors)

    public Outcome registerAnomaly(long detectorId, Window w, boolean includeSimulated, List<PredictionDtos.LabelWindow> labels, Boolean useAlerts,
                                   Integer toleranceMinutes) {
        ObservabilityDtos.DetectorDto detector = anomalies.get(detectorId);
        ObservabilityDtos.EvaluationResult r = anomalies.evaluate(detectorId, evaluateRequest(w, includeSimulated, labels, useAlerts, toleranceMinutes));
        Map<String, Object> metrics = anomalyMetrics(r);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("detectorId", detector.id());
        params.put("detectorName", detector.name());
        params.put("metricName", detector.metricName());
        params.put("algorithm", detector.algorithm());
        params.put("windowPoints", detector.windowPoints());
        params.put("threshold", detector.threshold());
        params.put("consecutivePoints", detector.consecutivePoints());
        params.put("minSamples", detector.minSamples());
        params.put("direction", detector.direction());
        params.put("seasonality", detector.seasonality());
        params.put("detectorIncludesSimulated", detector.includeSimulated());
        int labelCount = r.labels() == null ? 0 : r.labels().size();
        boolean ok = "EVALUATED".equals(r.status());
        return new Outcome(ok ? ModelStatus.TRAINED : ModelStatus.INSUFFICIENT_DATA,
                ok ? "Detector evaluated on " + labelCount + " labelled windows and " + r.detections().size() + " detections" : r.message(),
                "OBSERVABILITY_DETECTOR_" + detector.algorithm(), List.of(detector.metricName()), params, metrics, null, r.pointsEvaluated(), labelCount, 0,
                r.pointsEvaluated(), 1);
    }

    public Map<String, Object> evaluateAnomaly(long detectorId, Window w, boolean includeSimulated, List<PredictionDtos.LabelWindow> labels, Boolean useAlerts,
                                               Integer toleranceMinutes) {
        ObservabilityDtos.EvaluationResult r = anomalies.evaluate(detectorId, evaluateRequest(w, includeSimulated, labels, useAlerts, toleranceMinutes));
        Map<String, Object> m = anomalyMetrics(r);
        m.put("status", r.status());
        m.put("message", r.message());
        return m;
    }

    private static ObservabilityDtos.EvaluateRequest evaluateRequest(Window w, boolean includeSimulated, List<PredictionDtos.LabelWindow> labels,
                                                                     Boolean useAlerts, Integer toleranceMinutes) {
        List<ObservabilityDtos.LabelWindow> mapped = labels == null ? null
                : labels.stream().map(l -> new ObservabilityDtos.LabelWindow(l.from(), l.to(), l.label())).collect(Collectors.toList());
        return new ObservabilityDtos.EvaluateRequest(w.from(), w.to(), mapped, includeSimulated, useAlerts == null ? Boolean.TRUE : useAlerts,
                toleranceMinutes);
    }

    private static Map<String, Object> anomalyMetrics(ObservabilityDtos.EvaluationResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evaluation", "DETECTOR_EVALUATION");
        m.put("precision", r.precision());
        m.put("recall", r.recall());
        m.put("f1", r.f1());
        m.put("truePositives", r.truePositives());
        m.put("falsePositives", r.falsePositives());
        m.put("falseNegatives", r.falseNegatives());
        m.put("labels", r.labels() == null ? 0 : r.labels().size());
        m.put("detections", r.detections() == null ? 0 : r.detections().size());
        m.put("seriesEvaluated", r.seriesEvaluated());
        m.put("pointsEvaluated", r.pointsEvaluated());
        m.put("from", r.from());
        m.put("to", r.to());
        return m;
    }

    // ------------------------------------------------------------------ helpers

    static List<Double> round(double[] v) {
        return Arrays.stream(v).map(x -> Math.round(x * 1e8) / 1e8).boxed().toList();
    }

    static double[] doubles(List<Number> list) {
        return list == null ? new double[0] : list.stream().mapToDouble(Number::doubleValue).toArray();
    }

    static double num(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }
}

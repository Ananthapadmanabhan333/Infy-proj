package com.nexus360.prediction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response contracts of SA Predictive Intelligence ({@code /api/predictions}). */
public final class PredictionDtos {

    private PredictionDtos() {}

    public enum ModelType { SLA_BREACH, INCIDENT_RECURRENCE, CAPACITY_RISK, SERVICE_HEALTH, ANOMALY }

    public enum ModelStatus { TRAINED, ACTIVE, RETIRED, INSUFFICIENT_DATA, FAILED }

    /** Labelled time window for ANOMALY evaluations (passed to the observability detector evaluation). */
    public record LabelWindow(@NotNull Instant from, @NotNull Instant to, @Size(max = 200) String label) {}

    /**
     * Training request. Window defaults: last 365 days (ANOMALY: last 7 days, at most 31). {@code testFraction} is the share of the
     * window (time-ordered, most recent last) held out for the offline evaluation. {@code includeSimulated} lets simulated records
     * into the training data (the model is then flagged {@code simulated}).
     */
    public record TrainRequest(@NotNull ModelType type, Instant from, Instant to, Boolean includeSimulated,
                               @DecimalMin("0.1") @DecimalMax("0.5") Double testFraction,
                               @DecimalMin("0.0001") @DecimalMax("100") Double l2,
                               Long detectorId, @Valid @Size(max = 200) List<LabelWindow> labels, Boolean useAlerts,
                               @Min(0) @Max(60) Integer toleranceMinutes) {}

    /** Out-of-time evaluation of a stored model on a new window (default: since the model's training window ended). */
    public record EvaluateRequest(Instant from, Instant to, @Valid @Size(max = 200) List<LabelWindow> labels, Boolean useAlerts,
                                  @Min(0) @Max(60) Integer toleranceMinutes) {}

    public record ModelDto(Long id, ModelType type, String scopeKey, int version, String algorithm, ModelStatus status, String statusMessage,
                           List<String> features, Map<String, Object> params, Map<String, Object> metrics, Instant trainingFrom, Instant trainingTo,
                           Instant cutoffAt, int sampleCount, Integer positiveCount, int trainCount, int testCount, int minSamples,
                           boolean includeSimulated, boolean simulated, Long detectorId, Instant trainedAt, String trainedByName, Instant activatedAt,
                           String activatedByName, Instant retiredAt, String retiredByName, Instant lastEvaluatedAt, String driftStatus,
                           Instant driftCheckedAt, String method, Instant createdAt, Instant updatedAt) {}

    public record JobDto(Long id, String jobType, String trigger, ModelType modelType, Long modelId, String status, String message,
                         Map<String, Object> request, Map<String, Object> result, Instant startedAt, Instant finishedAt, long durationMs,
                         String requestedByName) {}

    /** One feature's share of a logistic-regression score: weight × standardised value. */
    public record Contribution(String feature, double value, double standardized, double weight, double contribution) {}

    /**
     * A served classification prediction (SLA_BREACH, INCIDENT_RECURRENCE). {@code status}: OK, NO_ACTIVE_MODEL (statistical fallback or
     * nothing), NOT_APPLICABLE (wrong subject state, see {@code message}), COMPLETE (the outcome is already known), INSUFFICIENT_DATA.
     * {@code basis}: MODEL | STATISTICAL_FALLBACK | NONE — never an AI/LLM result.
     */
    public record ClassificationPrediction(ModelType type, String status, String basis, Long modelId, Integer modelVersion, String algorithm,
                                           Long predictionId, String subjectType, Long subjectId, String subjectLabel, Double probability,
                                           Double threshold, Boolean predictedPositive, String riskLevel, Instant targetAt,
                                           Map<String, Object> features, List<Contribution> contributions, Map<String, Object> modelMetrics,
                                           String method, String howComputed, String message, boolean simulated, Instant generatedAt,
                                           Object fallback) {}

    public record IncidentPredictions(Long incidentId, String number, ClassificationPrediction slaBreach, ClassificationPrediction recurrence) {}

    public record ForecastPoint(Instant at, double predicted, double lower, double upper) {}

    /** {@code status}: OK | INSUFFICIENT_DATA | NO_ACTIVE_MODEL. */
    public record MetricForecast(String metric, String status, String method, Long predictionId, int historyPoints, List<ForecastPoint> history,
                                 List<ForecastPoint> forecast, Double sigma, String message) {}

    public record ServiceHealthForecast(Long serviceId, String serviceCode, String serviceName, String status, String basis, Long modelId,
                                        Integer modelVersion, String algorithm, int horizonDays, List<MetricForecast> metrics, Map<String, Object> modelMetrics,
                                        String howComputed, String message, Instant generatedAt) {}

    /**
     * Capacity forecast for one utilisation series: Holt trend with a 95 % interval. {@code hoursToThreshold} = first hour the point
     * forecast reaches the threshold; {@code earliestHours} = first hour the upper bound reaches it; {@code latestHours} = first hour the
     * lower bound reaches it. {@code atRisk} = the interval crosses the threshold within the horizon.
     */
    public record CapacitySeriesForecast(Long seriesId, String metric, Map<String, String> attributes, Long resourceId, String resourceServiceName,
                                         Long serviceId, Long ciId, String status, String method, Long predictionId, double threshold,
                                         String thresholdBasis, Double current, Double hoursToThreshold, Double earliestHours, Double latestHours,
                                         boolean atRisk, String riskLevel, int historyPoints, List<ForecastPoint> forecast, String message,
                                         boolean simulated) {}

    public record CapacityForecast(String status, String basis, Long modelId, Integer modelVersion, String algorithm, int horizonHours,
                                   List<CapacitySeriesForecast> series, Map<String, Object> modelMetrics, String howComputed, String message,
                                   Instant generatedAt) {}

    public record PredictionRecord(Long id, Long modelId, ModelType modelType, int modelVersion, String subjectType, Long subjectId, String subjectLabel,
                                   String target, double score, Double lower, Double upper, Double threshold, String riskLevel, Instant targetAt,
                                   Map<String, Object> features, Map<String, Object> explanation, String outcomeStatus, Boolean outcomeLabel,
                                   Double outcomeValue, Instant outcomeAt, String outcomeNote, boolean simulated, Instant createdAt) {}

    public record ReconcileResult(Instant ranAt, int checked, int known, int expired, int modelsChecked, int driftWarnings) {}

    /** Live performance of a model from recorded outcomes, compared with its offline evaluation. {@code driftStatus}: OK | DRIFT | INSUFFICIENT_DATA. */
    public record Performance(Long modelId, ModelType type, int version, ModelStatus status, Instant from, Instant to, int predictions,
                              int outcomes, int pending, Map<String, Object> offline, Map<String, Object> live, List<Map<String, Object>> overTime,
                              String driftStatus, List<String> driftReasons, String message, Instant computedAt) {}
}

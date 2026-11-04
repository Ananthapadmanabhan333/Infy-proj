package com.nexus360.observability;

import com.nexus360.alert.AlertSeverity;
import com.nexus360.incident.Level;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response bodies of the SA Observability API ({@code /api/observability/**}, {@code /api/otel/**}). */
public final class ObservabilityDtos {

    private ObservabilityDtos() {}

    // ------------------------------------------------------------------ sources

    /**
     * A telemetry source. {@code status}: NOT_CONNECTED until a test call (pull kinds) or the first authenticated delivery (OTLP)
     * succeeds, then CONNECTED with {@code lastVerifiedAt}; ERROR after a failed test/pull of a source that was connected.
     * {@code prepared}: the adapter is configuration + request builder + parser only (no scheduled pull). Secrets are never returned
     * ({@code hasSecret}); the OTLP token is shown once ({@link SourceSaved#token}) and afterwards only its {@code tokenHint}.
     */
    public record SourceDto(Long id, String name, TelemetrySourceKind kind, String status, boolean prepared, String baseUrl, String authType,
                            String username, boolean hasSecret, String tokenHint, Map<String, Object> config, boolean pullEnabled,
                            int pullIntervalSeconds, boolean active, Instant lastVerifiedAt, Instant lastTestedAt, String lastError,
                            Instant lastReceivedAt, Instant lastPulledAt, long receivedCount, Map<String, String> ingestPaths, boolean demo,
                            Instant createdAt, Instant updatedAt) {}

    /**
     * Create/update. {@code secret}: outbound credential (bearer token, basic password, API key / access token), write-only; on update
     * {@code null} keeps the stored secret. {@code config}: adapter configuration (see {@code GET /api/observability/sources/kinds}).
     * {@code kind} cannot change on update.
     */
    public record SourceRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @NotNull TelemetrySourceKind kind,
            @Size(max = 500) String baseUrl,
            @Pattern(regexp = "^(NONE|BEARER|BASIC|API_KEY)$") String authType,
            @Size(max = 200) String username,
            @Size(max = 4000) String secret,
            @Size(max = 20) Map<String, Object> config,
            Boolean pullEnabled,
            @Min(30) @Max(3600) Integer pullIntervalSeconds,
            Boolean active) {}

    /** {@code token}: the OTLP source token ({@code otsec_…}), only in the response that created or rotated it. */
    public record SourceSaved(SourceDto source, String token) {}

    public record TestResult(Long sourceId, String status, boolean success, Integer httpStatus, String message, Instant testedAt,
                             Instant lastVerifiedAt) {}

    public record PullResult(Long sourceId, int queries, int series, int points, int rejected, String message, Instant from, Instant to) {}

    public record KindInfo(TelemetrySourceKind kind, boolean prepared, boolean pullSupported, boolean testSupported, String defaultBaseUrl,
                           List<String> configKeys, String description) {}

    // ------------------------------------------------------------------ mapping rules and resources

    public record MappingRuleDto(Long id, String name, String attributeKey, String matchType, String matchValue, Long serviceId, String serviceName,
                                 Long ciId, String ciName, int sortOrder, boolean enabled, long matchCount, Instant lastMatchedAt, boolean demo,
                                 Instant createdAt, Instant updatedAt) {}

    public record MappingRuleRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @NotBlank @Size(max = 120) String attributeKey,
            @NotBlank @Pattern(regexp = "^(EXACT|REGEX)$") String matchType,
            @NotBlank @Size(max = 300) String matchValue,
            Long serviceId,
            Long ciId,
            @Min(0) @Max(10000) Integer sortOrder,
            Boolean enabled) {}

    public record ReapplyResult(int resources, int changed, int mapped, int unmapped) {}

    public record ResourceDto(Long id, String serviceName, String serviceNamespace, String environment, String hostName, Map<String, String> attributes,
                              Long serviceId, String serviceCode, String mappedServiceName, Long ciId, String ciName, Long mappingRuleId, Long sourceId,
                              boolean simulated, Instant firstSeenAt, Instant lastSeenAt) {}

    public record ResourceChangeDto(Long id, Long resourceId, String attributeKey, String fromValue, String toValue, Instant observedAt,
                                    boolean simulated) {}

    // ------------------------------------------------------------------ queries

    public record MetricNameDto(String name, String type, String unit, long seriesCount, Instant lastSeenAt, boolean simulated) {}

    /** {@code t}: bucket start; {@code v}: aggregated value; {@code samples}: raw points behind the bucket. */
    public record PointDto(Instant t, double v, long samples) {}

    public record SeriesDto(Long seriesId, String metric, String type, String unit, Map<String, String> attributes, Long resourceId, String resourceServiceName,
                            Long serviceId, Long ciId, boolean simulated, List<PointDto> points) {}

    /** {@code status}: OK or NO_DATA (with {@code message}); {@code simulated}: some returned data is simulated. */
    public record SeriesResponse(String metric, String aggregation, Instant from, Instant to, long stepSeconds, boolean includeSimulated, boolean simulated,
                                 String status, String message, List<SeriesDto> series) {}

    public record LogDto(Long id, Instant timestamp, String severity, String severityText, String body, boolean bodyTruncated,
                         Map<String, String> attributes, String traceId, String spanId, Long resourceId, String resourceServiceName, Long serviceId,
                         boolean simulated) {}

    public record TraceSummary(String traceId, String rootName, String rootServiceName, Long serviceId, Instant startTime, double durationMs,
                               int spanCount, int errorCount, boolean simulated) {}

    public record SpanNode(String spanId, String parentSpanId, String name, String kind, String serviceName, Long serviceId, Long resourceId,
                           Instant startTime, Instant endTime, double durationMs, double offsetMs, String statusCode, String statusMessage,
                           Map<String, String> attributes, boolean simulated, List<SpanNode> children) {}

    public record TraceDetail(String traceId, Instant startTime, Instant endTime, double durationMs, int spanCount, int errorCount, boolean simulated,
                              boolean truncated, List<SpanNode> roots) {}

    /**
     * RED summary of a service: {@code basis} SPANS (server/root spans), METRICS ({@code http.server.request.duration} histograms) or
     * NONE ({@code status} INSUFFICIENT_DATA). Rates are per minute over the window; durations in milliseconds.
     */
    public record RedSummary(Long serviceId, String serviceCode, String serviceName, Instant from, Instant to, String basis, long requests,
                             Double ratePerMinute, long errors, Double errorRate, Double durationP95Ms, Double durationAvgMs, boolean simulated,
                             String status, String message) {}

    // ------------------------------------------------------------------ retention

    public record RetentionDto(int rawDays, int rollupDays, int logsDays, int tracesDays) {}

    public record RetentionRequest(@NotNull @Min(1) @Max(90) Integer rawDays, @NotNull @Min(1) @Max(3650) Integer rollupDays,
                                   @NotNull @Min(1) @Max(365) Integer logsDays, @NotNull @Min(1) @Max(90) Integer tracesDays) {}

    public record PurgeResult(Instant ranAt, long points, long rollups, long logs, long spans, long series, long resources) {}

    // ------------------------------------------------------------------ anomaly detection

    public record DetectorRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 200) String metricName,
            Long serviceId,
            Long ciId,
            @Size(max = 10) Map<String, @Size(max = 300) String> attributeFilters,
            @Pattern(regexp = "^(avg|max|min|sum|p95)$") String valueAggregation,
            @Pattern(regexp = "^(EWMA|MEDIAN_MAD)$") String algorithm,
            @Min(5) @Max(10080) Integer windowPoints,
            @DecimalMin("1.0") @DecimalMax("20.0") Double threshold,
            @Min(1) @Max(60) Integer consecutivePoints,
            @Min(5) @Max(100000) Integer minSamples,
            @Pattern(regexp = "^(UP|DOWN|BOTH)$") String direction,
            @Pattern(regexp = "^(NONE|HOUR_OF_WEEK|AUTO)$") String seasonality,
            Boolean includeSimulated,
            Boolean raiseAlert,
            AlertSeverity alertSeverity,
            Boolean createIncident,
            Boolean enabled) {}

    public record DetectorDto(Long id, String name, String description, String metricName, Long serviceId, String serviceName, Long ciId, String ciName,
                              Map<String, String> attributeFilters, String valueAggregation, String algorithm, int windowPoints, double threshold,
                              int consecutivePoints, int minSamples, String direction, String seasonality, boolean includeSimulated, boolean raiseAlert,
                              AlertSeverity alertSeverity, boolean createIncident, boolean enabled, Instant lastRunAt, EvaluationResult lastEvaluation,
                              Instant lastEvaluatedAt, long openAnomalies, boolean demo, Instant createdAt, Instant updatedAt) {}

    /** {@code status}: OK, INSUFFICIENT_DATA ("N of M samples"), NO_SERIES or DISABLED. */
    public record RunResult(Long detectorId, int seriesEvaluated, int pointsEvaluated, int anomaliesOpened, int anomaliesUpdated, int anomaliesResolved,
                            int alertsRaised, String status, String message) {}

    public record LabelWindow(@NotNull Instant from, @NotNull Instant to, @Size(max = 200) String label) {}

    /**
     * Evaluation over labelled history. Labels: simulated fault windows ({@code useSimulatedFaults}, default true), past alerts on the
     * detector's service that were not raised by this detector ({@code useAlerts}, default true) and explicit {@code labels}.
     * {@code toleranceMinutes}: a detection within this distance of a label counts as matching (default 5).
     */
    public record EvaluateRequest(Instant from, Instant to, @Valid @Size(max = 200) List<LabelWindow> labels, Boolean useSimulatedFaults, Boolean useAlerts,
                                  @Min(0) @Max(60) Integer toleranceMinutes) {}

    public record WindowDto(Long seriesId, Instant from, Instant to, double peakScore, double observed, double expected) {}

    /** {@code source}: FAULT_WINDOW, ALERT or MANUAL. */
    public record LabelDto(String source, String reference, Instant from, Instant to, boolean detected) {}

    /**
     * {@code status}: EVALUATED or INSUFFICIENT_DATA (no labels, or fewer points than {@code minSamples}); precision = detections that
     * match a label / detections, recall = labels matched by a detection / labels (null when undefined).
     */
    public record EvaluationResult(Long detectorId, String status, String message, Instant from, Instant to, int seriesEvaluated, int pointsEvaluated,
                                   List<WindowDto> detections, List<LabelDto> labels, int truePositives, int falsePositives, int falseNegatives,
                                   Double precision, Double recall, Double f1, Map<String, Object> params, Instant evaluatedAt) {}

    public record AnomalyDto(Long id, Long detectorId, String detectorName, Long seriesId, String metricName, Map<String, String> seriesAttributes,
                             Long serviceId, String serviceName, Long ciId, String ciName, String status, String direction, Instant startedAt, Instant endedAt,
                             Instant lastPointAt, int pointCount, double peakScore, double observedValue, double expectedValue, double expectedLower,
                             double expectedUpper, Map<String, Object> modelParams, Long alertId, String alertNumber, Long correlationGroupId,
                             boolean simulated, Instant createdAt) {}

    // ------------------------------------------------------------------ correlation

    /** One explanation: {@code code} TIME_WINDOW, SAME_CI, SAME_SERVICE, TOPOLOGY, SHARED_LABELS, SAME_FINGERPRINT, DEPLOYMENT, CHANGE, MANUAL. */
    public record Reason(String code, String text, double weight) {}

    public record MemberDto(Long id, String memberType, Long alertId, String alertNumber, Long anomalyId, String title, String severity, String status,
                            Long serviceId, String serviceName, Long ciId, String ciName, Instant firstSeenAt, double score, List<Reason> reasons,
                            String addedBy, Instant joinedAt, boolean simulated) {}

    /** {@code members} is only filled by the detail endpoint. */
    public record GroupDto(Long id, String title, String status, double score, List<Reason> reasons, int memberCount, Long rootCiId, String rootCiName,
                           Long rootServiceId, String rootServiceName, String rootReason, Long incidentId, String incidentNumber, Instant firstSeenAt,
                           Instant lastActivityAt, String highestSeverity, boolean simulated, String dismissedReason, String dismissedByName,
                           Instant dismissedAt, List<MemberDto> members) {}

    public record LinkGroupRequest(@NotNull Long incidentId) {}

    public record ConvertGroupRequest(@Size(min = 5, max = 200) String title, Level impact, Level urgency) {}

    public record SplitRequest(@NotEmpty @Size(max = 100) List<@NotNull Long> memberIds, @Size(max = 300) String title) {}

    public record MergeRequest(@NotEmpty @Size(max = 20) List<@NotNull Long> groupIds) {}

    public record DismissRequest(@NotBlank @Size(min = 3, max = 1000) String reason) {}
}

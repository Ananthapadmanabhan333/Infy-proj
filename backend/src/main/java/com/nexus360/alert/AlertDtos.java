package com.nexus360.alert;

import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.Level;
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

public final class AlertDtos {

    private AlertDtos() {}

    public record AlertSummary(Long id, String number, String title, AlertSeverity severity, Alert.Status status, boolean firing,
                               Alert.SourceKind sourceKind, Long sourceId, String sourceName, String groupKey, Long serviceId, String serviceName, Long ciId,
                               String ciName, Long teamId, String teamName, Long incidentId, String incidentNumber, int occurrenceCount, Instant firstSeenAt,
                               Instant lastSeenAt, Instant acknowledgedAt, String acknowledgedByName, Instant resolvedAt, int escalationLevel,
                               Instant nextEscalationAt, boolean demo, boolean simulated, Long correlationGroupId) {

        /** Release 4: the correlation group the alert is currently a member of (null when none). */
        public AlertSummary withCorrelationGroup(Long groupId) {
            return new AlertSummary(id, number, title, severity, status, firing, sourceKind, sourceId, sourceName, groupKey, serviceId, serviceName, ciId,
                    ciName, teamId, teamName, incidentId, incidentNumber, occurrenceCount, firstSeenAt, lastSeenAt, acknowledgedAt, acknowledgedByName,
                    resolvedAt, escalationLevel, nextEscalationAt, demo, simulated, groupId);
        }
    }

    public record EventDto(Long id, String type, String actorName, String note, Map<String, Object> details, Instant at) {}

    /**
     * {@code allowedActions}: ACKNOWLEDGE, RESOLVE, SUPPRESS, UNSUPPRESS, CONVERT, LINK_INCIDENT, ASSIGN (for staff; empty for auditors).
     * {@code escalationPolicy}: name of the policy that would escalate the alert while it is OPEN (null = none applies).
     */
    public record AlertDetail(AlertSummary alert, String description, String fingerprint, Map<String, String> labels, Map<String, String> annotations,
                              String externalUrl, Instant startsAt, Instant endsAt, String resolvedByName, String resolutionNote, Instant suppressedAt,
                              Instant suppressedUntil, Long suppressionRuleId, String suppressionRuleName, Long routingRuleId, String routingRuleName,
                              Long integrationId, String integrationName, IncidentSummary incident, List<AlertSummary> groupAlerts, List<EventDto> history,
                              List<String> allowedActions, String escalationPolicy) {}

    public record AlertCounts(long open, long acknowledged, long suppressed, long active, long resolvedLast24h, Map<String, Long> bySeverity,
                              long criticalOpen, long escalated, long withIncident) {}

    public record AlertGroup(String groupKey, String title, long alertCount, long firingCount, AlertSeverity maxSeverity, long occurrences,
                             Instant firstSeenAt, Instant lastSeenAt, Long incidentId, String incidentNumber, Alert.SourceKind sourceKind,
                             Map<String, Long> byStatus) {}

    /** Manual alert ({@code POST /alerts}, source MANUAL); same fields as the generic webhook format. */
    public record CreateAlertRequest(
            @NotBlank @Size(min = 3, max = 300) String title,
            @Size(max = 10_000) String description,
            AlertSeverity severity,
            @Pattern(regexp = "^(firing|resolved)$") String status,
            @Size(max = 128) String fingerprint,
            @Size(max = 300) String groupKey,
            Long serviceId,
            Long ciId,
            @Size(max = 50) Map<String, String> labels,
            @Size(max = 50) Map<String, String> annotations,
            @Size(max = 500) @Pattern(regexp = "^https?://.+") String externalUrl) {}

    public record NoteRequest(@Size(max = 1000) String note) {}

    /** {@code minutes}: suppress for this long (5 minutes to 7 days); {@code null} = until the alert resolves or is unsuppressed. */
    public record SuppressRequest(@Min(5) @Max(10_080) Integer minutes, @NotBlank @Size(min = 3, max = 1000) String reason) {}

    public record ConvertRequest(@Size(min = 5, max = 200) String title, @Size(max = 5000) String description, Level impact, Level urgency) {}

    public record LinkIncidentRequest(@NotNull Long incidentId) {}

    public record AssignTeamRequest(@NotNull Long teamId, @Size(max = 1000) String note) {}

    /**
     * {@code note}: ACKNOWLEDGE / RESOLVE note. SUPPRESS uses {@code minutes} (5 minutes to 7 days; {@code null} = until the alert resolves or is
     * unsuppressed) and {@code reason} (required for SUPPRESS, {@code 400} otherwise), exactly like {@link SuppressRequest}.
     */
    public record BulkRequest(@NotEmpty @Size(max = 100) List<@NotNull Long> ids,
                              @NotNull @Pattern(regexp = "^(ACKNOWLEDGE|RESOLVE|SUPPRESS)$") String action, @Size(max = 1000) String note,
                              @Min(5) @Max(10_080) Integer minutes, @Size(min = 3, max = 1000) String reason) {}

    public record BulkResult(int processed, int skipped, List<String> messages) {}

    /** Result of converting or linking: the alert and the incident (with the intake pipeline's duplicate outcome). */
    public record ConvertResult(AlertDetail alert, Long incidentId, String incidentNumber, boolean duplicate, String parentNumber, int linkedAlerts) {}

    public record AlertOutcome(Long id, String number, String action, Alert.Status status, Long incidentId, String incidentNumber) {}

    /** Webhook response: {@code outcome} PROCESSED, IGNORED (nothing to record) or DUPLICATE (delivery id already received). */
    public record WebhookResult(String outcome, String deliveryId, String message, List<AlertOutcome> alerts) {}

    // ------------------------------------------------------------------ sources

    public record SourceDto(Long id, String name, AlertSource.Provider provider, Long defaultServiceId, String defaultServiceName, Long defaultCiId,
                            String defaultCiName, AlertSeverity defaultSeverity, String topicArn, AlertSource.SubscriptionStatus subscriptionStatus,
                            String subscriptionArn, Instant subscriptionConfirmedAt, boolean active, String webhookPath, String authentication,
                            Instant lastReceivedAt, boolean demo, Instant createdAt) {}

    /** {@code secret} only when the source is created or its secret rotated (null for CLOUDWATCH, which uses SNS signatures). */
    public record SourceSaveResult(SourceDto source, String secret) {}

    public record SourceRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull AlertSource.Provider provider,
            Long defaultServiceId,
            Long defaultCiId,
            AlertSeverity defaultSeverity,
            @Size(max = 300) String topicArn,
            Boolean active) {}

    public record DeliveryDto(Long id, String deliveryId, String eventType, String outcome, String message, int alertCount, Instant receivedAt) {}

    // ------------------------------------------------------------------ rules

    public record SuppressionRuleDto(Long id, String name, String description, boolean enabled, MatchCriteria match, Instant startsAt, Instant endsAt,
                                     String dailyStart, String dailyEnd, List<Integer> daysOfWeek, boolean activeNow, boolean expired, long matchCount,
                                     Instant lastMatchedAt, String createdByName, boolean demo, Instant createdAt, Instant updatedAt) {}

    /** {@code dailyStart}/{@code dailyEnd}: "HH:mm" in the organisation time zone (both or neither; may cross midnight). */
    public record SuppressionRuleRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @Size(max = 500) String description,
            Boolean enabled,
            MatchCriteria match,
            Instant startsAt,
            Instant endsAt,
            @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String dailyStart,
            @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String dailyEnd,
            @Size(max = 7) List<@Min(1) @Max(7) Integer> daysOfWeek) {}

    public record RoutingRuleDto(Long id, String name, String description, boolean enabled, int sortOrder, MatchCriteria match, Long teamId, String teamName,
                                 Long serviceId, String serviceName, Long ciId, String ciName, boolean createIncident, AlertSeverity incidentMinSeverity,
                                 long matchCount, Instant lastMatchedAt, String createdByName, boolean demo, Instant createdAt, Instant updatedAt) {}

    public record RoutingRuleRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @Size(max = 500) String description,
            Boolean enabled,
            @Min(0) @Max(10_000) Integer sortOrder,
            MatchCriteria match,
            Long teamId,
            Long serviceId,
            Long ciId,
            Boolean createIncident,
            AlertSeverity incidentMinSeverity) {}
}

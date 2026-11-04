package com.nexus360.deployment;

import com.nexus360.incident.IncidentDtos.IncidentSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class DeploymentDtos {

    private DeploymentDtos() {}

    public record DeploymentDto(Long id, String number, Long releaseId, String releaseNumber, String releaseName, Long changeId, String changeNumber,
                                Long serviceId, String serviceName, Deployment.Environment environment, String version, String commitSha,
                                String pipelineUrl, Deployment.Status status, Deployment.Source source, Long sourceId, String sourceName,
                                String externalId, String actorName, Instant startedAt, Instant finishedAt, Long durationSeconds, Long rollbackOfId,
                                String rollbackOfNumber, Instant rolledBackAt, String rollbackReason, String notes, boolean demo, Instant createdAt,
                                boolean simulated) {}

    /** Deterministic deployment risk (environment, service tier, change linkage, recent failures, freeze). */
    public record RiskFactor(String key, String label, int points) {}

    public record DeploymentRisk(String level, int score, List<RiskFactor> factors, String basis) {}

    public record CorrelatedIncident(IncidentSummary incident, long minutesAfterStart) {}

    public record DeploymentDetail(DeploymentDto deployment, DeploymentRisk risk, List<CorrelatedIncident> correlatedIncidents,
                                   int correlationHours, List<DeploymentDto> rollbacks) {}

    public record DeploymentRequest(
            @NotNull Long serviceId,
            @NotNull Deployment.Environment environment,
            @NotBlank @Size(max = 80) String version,
            @Pattern(regexp = "^[0-9a-fA-F]{7,64}$") String commitSha,
            @Size(max = 500) @Pattern(regexp = "^https?://.+") String pipelineUrl,
            Deployment.Status status,
            Long releaseId,
            Long changeId,
            @Size(max = 2000) String notes,
            Instant startedAt) {}

    public record StatusRequest(@NotNull Deployment.Status status, @Size(max = 2000) String notes) {}

    /** {@code targetVersion}: defaults to the previous successful version on the same service and environment. */
    public record RollbackRequest(@NotBlank @Size(min = 5, max = 1000) String reason, @Size(max = 80) String targetVersion) {}

    public record ReleaseDto(Long id, String number, String name, String version, String description, Release.Status status, Long serviceId,
                             String serviceName, Long changeId, String changeNumber, Long ownerId, String ownerName, Instant targetAt,
                             long deploymentCount, boolean demo, Instant createdAt, Instant updatedAt) {}

    public record ReleaseDetail(ReleaseDto release, List<DeploymentDto> deployments) {}

    public record ReleaseRequest(@NotBlank @Size(max = 150) String name, @NotBlank @Size(max = 80) String version, @Size(max = 2000) String description,
                                 Release.Status status, Long serviceId, Long changeId, Instant targetAt) {}

    public record SourceDto(Long id, String name, DeploymentSource.Provider provider, Long serviceId, String serviceName,
                            Deployment.Environment defaultEnvironment, boolean active, String webhookPath, Instant lastDeliveryAt, boolean demo,
                            Instant createdAt) {}

    /** {@code secret} is returned only when the source is created or its secret rotated; it is stored encrypted. */
    public record SourceSaveResult(SourceDto source, String secret) {}

    public record SourceRequest(@NotBlank @Size(max = 120) String name, @NotNull DeploymentSource.Provider provider, @NotNull Long serviceId,
                                Deployment.Environment defaultEnvironment, Boolean active) {}

    public record DeliveryDto(Long id, String deliveryId, String eventType, WebhookDelivery.Outcome outcome, String message, Long deploymentId,
                              Instant receivedAt) {}

    /** Webhook response: PROCESSED, IGNORED (event not relevant) or DUPLICATE (delivery id already seen). */
    public record WebhookResult(String outcome, String deliveryId, Long deploymentId, String deploymentNumber, String message) {}

    public record CalendarDay(LocalDate date, long succeeded, long failed, long inProgress, long rolledBack, List<DeploymentDto> deployments) {}

    public record DeploymentCalendar(Instant from, Instant to, List<CalendarDay> days) {}

    public record DeploymentMetrics(LocalDate from, LocalDate to, long total, Map<String, Long> byEnvironment, Map<String, Long> byStatus,
                                    Double successRate, Double failureRate, Double rollbackRate, Double avgDurationMinutes,
                                    long productionWithoutChange, long followedByIncidents) {}
}

package com.nexus360.remediation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RemediationDtos {

    private RemediationDtos() {}

    public record MetaDto(
            List<String> connectorKinds,
            List<String> stepKinds,
            List<String> triggerKinds,
            List<String> runStatuses,
            List<String> versionStatuses
    ) {}

    public record ActionTemplateDto(
            String id,
            String name,
            String description,
            Map<String, Object> parametersSchema,
            Map<String, Object> targetScope
    ) {}

    public record ExecutionSystemDto(
            Long id,
            String name,
            String kind,
            String endpointUrl,
            String credentialScope,
            boolean credentialsConfigured,
            List<Map<String, Object>> actionTemplates,
            String status,
            Instant lastTestedAt,
            String lastError,
            boolean enabled,
            boolean demo,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record ExecutionSystemRequest(
            @NotBlank @Size(max = 140) String name,
            @NotBlank @Size(max = 30) String kind,
            @Size(max = 500) String endpointUrl,
            @Size(max = 4000) String credentials,
            @Size(max = 500) String credentialScope,
            List<Map<String, Object>> actionTemplates,
            Boolean enabled
    ) {}

    public record PlaybookVersionDto(
            Long id,
            Long playbookId,
            int version,
            String status,
            List<String> triggerKinds,
            Map<String, Object> parametersSchema,
            List<Map<String, Object>> steps,
            Map<String, Object> recoveryCriteria,
            Long approvalId,
            Long createdById,
            String createdByName,
            Long approvedById,
            String approvedByName,
            Instant approvedAt,
            Instant createdAt
    ) {}

    public record PlaybookDto(
            Long id,
            String code,
            String name,
            String description,
            String targetEnvironment,
            List<String> preApprovedAlertTypes,
            int maxBlastRadius,
            int rateLimitPerHour,
            boolean requireLinkedRecord,
            Integer activeVersion,
            boolean enabled,
            boolean demo,
            Instant createdAt,
            Instant updatedAt,
            List<PlaybookVersionDto> versions
    ) {}

    public record PlaybookCreateRequest(
            @NotBlank @Size(max = 60) String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            String targetEnvironment,
            List<String> preApprovedAlertTypes,
            Integer maxBlastRadius,
            Integer rateLimitPerHour,
            Boolean requireLinkedRecord,
            Boolean enabled,
            List<String> triggerKinds,
            Map<String, Object> parametersSchema,
            List<Map<String, Object>> steps,
            Map<String, Object> recoveryCriteria
    ) {}

    public record PlaybookVersionRequest(
            List<String> triggerKinds,
            Map<String, Object> parametersSchema,
            List<Map<String, Object>> steps,
            Map<String, Object> recoveryCriteria
    ) {}

    public record PolicyCheckDto(
            String check,
            boolean passed,
            String detail
    ) {}

    public record RemediationRunDto(
            Long id,
            Long playbookId,
            String playbookCode,
            String playbookName,
            int playbookVersion,
            String triggerKind,
            String status,
            String targetKey,
            String environment,
            Long incidentId,
            Long changeId,
            Long alertId,
            Map<String, Object> parameters,
            List<PolicyCheckDto> policyChecks,
            String overrideReason,
            Long overriddenById,
            String overriddenByName,
            Long approvalId,
            Map<String, Object> verificationResult,
            String failureReason,
            Long requestedById,
            String requestedByName,
            boolean simulated,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record RemediationRunStepDto(
            Long id,
            Long runId,
            int stepOrder,
            String name,
            String kind,
            boolean isRollback,
            String status,
            Map<String, Object> requestSummary,
            Map<String, Object> responseSummary,
            int attempts,
            Long durationMs,
            String errorMessage,
            Instant startedAt,
            Instant completedAt
    ) {}

    public record RemediationAuditDto(
            Long id,
            String eventType,
            Long runId,
            Long playbookId,
            Long actorId,
            String actorName,
            Map<String, Object> payload,
            Instant createdAt,
            String prevHash,
            String hash
    ) {}

    public record RemediationRunDetailDto(
            RemediationRunDto run,
            List<RemediationRunStepDto> steps,
            List<RemediationAuditDto> auditEntries
    ) {}

    public record StartRunRequest(
            Long playbookId,
            String playbookCode,
            @NotBlank @Size(max = 160) String targetKey,
            String environment,
            Long incidentId,
            Long changeId,
            Long alertId,
            String triggerKind,
            Map<String, Object> parameters,
            Integer simulateFailureAtStep,
            @Size(max = 500) String overrideReason,
            Boolean autoExecute,
            Boolean simulated
    ) {}

    public record OverrideRunRequest(
            @NotBlank @Size(max = 500) String reason
    ) {}

    public record AuditVerifyDto(
            boolean valid,
            long totalRecords,
            Long firstBrokenId,
            String message
    ) {}
}

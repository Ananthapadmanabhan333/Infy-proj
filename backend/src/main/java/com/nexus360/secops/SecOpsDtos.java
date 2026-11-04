package com.nexus360.secops;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SecOpsDtos {

    private SecOpsDtos() {}

    public record SecurityOverviewDto(
            long openIncidents,
            long criticalFindings,
            long highFindings,
            long openFindings,
            long openCorrelationGroups,
            long slaBreachedFindings,
            String riskFormula,
            boolean simulated
    ) {}

    public record SecurityIncidentDto(
            Long id,
            String number,
            String title,
            String description,
            String category,
            String severity,
            String status,
            Long linkedIncidentId,
            Long affectedCiId,
            Long affectedServiceId,
            String affectedUserEmail,
            Long assignedToId,
            String assignedToName,
            List<Map<String, Object>> indicators,
            List<Map<String, Object>> evidenceNotes,
            String lessonsLearned,
            boolean simulated,
            boolean demo,
            Long createdById,
            String createdByName,
            Instant createdAt,
            Instant updatedAt,
            Instant closedAt
    ) {}

    public record SecurityIncidentTaskDto(
            Long id,
            Long securityIncidentId,
            int taskOrder,
            String title,
            String phase,
            String status,
            String remediationPlaybookCode,
            String completedByName,
            Instant completedAt
    ) {}

    public record SecurityIncidentDetailDto(
            SecurityIncidentDto incident,
            List<SecurityIncidentTaskDto> tasks
    ) {}

    public record CreateSecurityIncidentRequest(
            @NotBlank @Size(max = 240) String title,
            @Size(max = 5000) String description,
            String category,
            String severity,
            Long linkedIncidentId,
            Long affectedCiId,
            Long affectedServiceId,
            @Size(max = 160) String affectedUserEmail,
            Long assignedToId,
            List<Map<String, Object>> indicators,
            String templateCode,
            Boolean simulated
    ) {}

    public record TransitionSecurityIncidentRequest(
            @NotBlank String status,
            @Size(max = 5000) String lessonsLearned
    ) {}

    public record AddSecurityNoteRequest(
            @Size(max = 4000) String note,
            Map<String, Object> indicator,
            String indicatorType,
            String indicatorValue,
            String indicatorConfidence
    ) {}

    public record VulnerabilitySourceDto(
            Long id,
            String name,
            String format,
            String scannerName,
            String tokenPrefix,
            boolean enabled,
            Instant lastIngestedAt,
            boolean demo,
            Instant createdAt
    ) {}

    public record VulnerabilitySourceCreatedDto(
            VulnerabilitySourceDto source,
            String rawToken
    ) {}

    public record CreateVulnerabilitySourceRequest(
            @NotBlank @Size(max = 140) String name,
            @NotBlank @Size(max = 30) String format,
            @NotBlank @Size(max = 80) String scannerName
    ) {}

    public record VulnerabilityFindingDto(
            Long id,
            Long sourceId,
            String dedupeKey,
            String cveId,
            String cweId,
            String title,
            String scanner,
            String assetName,
            Long ciId,
            Long serviceId,
            String packageName,
            String installedVersion,
            String fixedVersion,
            double cvssBase,
            String cvssVector,
            double assetCriticality,
            boolean internetExposed,
            boolean exploitKnown,
            double riskScore,
            String riskBand,
            int slaDays,
            String status,
            Instant acceptedUntil,
            String acceptanceReason,
            Long approvalId,
            Instant firstSeenAt,
            Instant lastSeenAt,
            boolean simulated
    ) {}

    public record VulnerabilityIngestRequest(
            Long sourceId,
            String format,
            Boolean fullScan,
            JsonNode payload,
            Boolean simulated
    ) {}

    public record VulnerabilityIngestResultDto(
            int ingested,
            int updated,
            int autoClosed,
            List<VulnerabilityFindingDto> findings
    ) {}

    public record AcceptRiskRequest(
            @Size(max = 500) String reason,
            @Size(max = 500) String acceptanceReason,
            Instant acceptedUntil,
            Integer days
    ) {}

    public record SecurityCorrelationGroupDto(
            Long id,
            String groupKey,
            String title,
            String targetAsset,
            String targetUser,
            String severity,
            String status,
            Map<String, Object> signalSummary,
            Long securityIncidentId,
            boolean simulated,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record SecurityResponseTemplateDto(
            Long id,
            String code,
            String name,
            String category,
            String description,
            List<Map<String, Object>> tasks,
            String recommendedPlaybookCode,
            boolean demo
    ) {}
}

package com.nexus360.change;

import com.nexus360.approval.ApprovalService.ApprovalDto;
import com.nexus360.incident.Category;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.Level;
import com.nexus360.incident.Priority;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ChangeDtos {

    private ChangeDtos() {}

    public record ServiceRef(Long id, String code, String name) {}

    public record ChangeSummary(Long id, String number, String title, Change.Type type, ChangeStatus status, Change.Risk risk, int riskScore,
                                Priority priority, Instant plannedStart, Instant plannedEnd, List<ServiceRef> services, Long assigneeId,
                                String assigneeName, Long teamId, String teamName, Long requesterId, String requesterName, Long approvalId,
                                String approvalStatus, boolean pirRequired, boolean pirDone, Instant createdAt, Instant updatedAt, boolean demo) {}

    /** Another change overlapping in time on a shared service or configuration item. */
    public record Conflict(Long changeId, String number, String title, ChangeStatus status, Instant plannedStart, Instant plannedEnd,
                           List<String> services, List<String> cis) {}

    public record FreezeRef(Long id, String name, Instant startsAt, Instant endsAt) {}

    public record LinkedIncident(ChangeIncident.Relation relation, IncidentSummary incident) {}

    public record DeploymentRef(Long id, String number, String environment, String version, String status, Instant startedAt) {}

    public record ChangeDetail(Long id, String number, String title, String description, Change.Type type, ChangeStatus status, Category category,
                               Priority priority, Change.Risk risk, int riskScore, Map<String, String> riskAnswers, List<ChangeRisk.Factor> riskFactors,
                               Instant riskAssessedAt, Level impact, int affectedUsers, List<String> affectedCis, String justification,
                               String implementationPlan, String rollbackPlan, String testPlan, Instant plannedStart, Instant plannedEnd,
                               Instant actualStart, Instant actualEnd, List<ServiceRef> services, Long requesterId, String requesterName,
                               Long assigneeId, String assigneeName, Long teamId, String teamName, Long templateId, String templateName,
                               ApprovalDto approval, Long problemId, String problemNumber, String outcomeNotes, boolean pirRequired, String pirNotes,
                               Instant pirCompletedAt, String pirByName, String conflictOverrideReason, Instant submittedAt, Instant approvedAt,
                               Instant closedAt, List<Conflict> conflicts, List<FreezeRef> freezeWindows, List<LinkedIncident> linkedIncidents,
                               List<DeploymentRef> deployments, List<ChangeStatus> allowedTransitions, Instant createdAt, Instant updatedAt,
                               boolean demo, List<com.nexus360.cmdb.CmdbDtos.CiRef> cis) {}

    /**
     * Create / update (update: DRAFT, SUBMITTED, ASSESSMENT or REJECTED only; {@code null} lists leave values unchanged).
     * STANDARD changes need {@code templateId}; plans, services and category default from the template.
     */
    public record ChangeRequest(
            @Size(min = 5, max = 200) String title,
            @Size(max = 10_000) String description,
            Change.Type type,
            Category category,
            Priority priority,
            Level impact,
            @Min(0) @Max(1_000_000) Integer affectedUsers,
            @Size(max = 30) List<@NotBlank @Size(max = 120) String> affectedCis,
            @Size(max = 5000) String justification,
            @Size(max = 20_000) String implementationPlan,
            @Size(max = 20_000) String rollbackPlan,
            @Size(max = 20_000) String testPlan,
            Instant plannedStart,
            Instant plannedEnd,
            @Size(max = 20) List<Long> serviceIds,
            Long assigneeId,
            Long teamId,
            Long templateId,
            Long problemId,
            Map<String, String> riskAnswers,
            @Size(max = 30) List<Long> ciIds) {

        /** BE-2 shape without CI links ({@code ciIds} null = links follow {@code affectedCis} names). */
        public ChangeRequest(String title, String description, Change.Type type, Category category, Priority priority, Level impact, Integer affectedUsers,
                             List<String> affectedCis, String justification, String implementationPlan, String rollbackPlan, String testPlan,
                             Instant plannedStart, Instant plannedEnd, List<Long> serviceIds, Long assigneeId, Long teamId, Long templateId, Long problemId,
                             Map<String, String> riskAnswers) {
            this(title, description, type, category, priority, impact, affectedUsers, affectedCis, justification, implementationPlan, rollbackPlan, testPlan,
                    plannedStart, plannedEnd, serviceIds, assigneeId, teamId, templateId, problemId, riskAnswers, null);
        }
    }

    /**
     * {@code reason}: required for CANCELLED and for sending back to DRAFT; {@code plannedStart}/{@code plannedEnd}: window when
     * scheduling; {@code outcomeNotes}: required for FAILED and ROLLED_BACK; {@code riskAnswers}: the questionnaire when
     * moving to ASSESSMENT; {@code overrideConflicts} + {@code overrideReason}: schedule despite conflicts (change approvers).
     */
    public record TransitionRequest(@NotNull ChangeStatus status, @Size(max = 1000) String reason, Instant plannedStart, Instant plannedEnd,
                                    @Size(max = 10_000) String outcomeNotes, Map<String, String> riskAnswers, Boolean overrideConflicts,
                                    @Size(max = 1000) String overrideReason) {}

    public record ReviewRequest(@NotBlank @Size(min = 10, max = 10_000) String notes) {}

    public record LinkIncidentRequest(@NotNull Long incidentId, @NotNull ChangeIncident.Relation relation) {}

    public record HistoryDto(Instant at, String actorName, String action, String fromValue, String toValue, String note) {}

    public record TemplateDto(Long id, String code, String name, String description, Category category, String implementationPlan,
                              String rollbackPlan, String testPlan, int defaultDurationMinutes, List<Long> serviceIds, boolean active, boolean demo) {}

    public record TemplateRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,40}$") String code,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 1000) String description,
            Category category,
            @NotBlank @Size(max = 20_000) String implementationPlan,
            @NotBlank @Size(max = 20_000) String rollbackPlan,
            @Size(max = 20_000) String testPlan,
            @Min(5) @Max(10_080) Integer defaultDurationMinutes,
            @Size(max = 20) List<Long> serviceIds,
            Boolean active) {}

    public record FreezeDto(Long id, String name, String reason, Instant startsAt, Instant endsAt, List<Long> serviceIds, boolean active,
                            boolean demo) {}

    public record FreezeRequest(@NotBlank @Size(max = 150) String name, @Size(max = 1000) String reason, @NotNull Instant startsAt,
                                @NotNull Instant endsAt, @Size(max = 50) List<Long> serviceIds, Boolean active) {}

    public record CalendarEntry(Long id, String number, String title, Change.Type type, ChangeStatus status, Change.Risk risk, Instant start,
                                Instant end, Instant actualStart, Instant actualEnd, List<ServiceRef> services, List<String> conflictsWith,
                                boolean inFreeze) {}

    public record CalendarConflict(String changeA, String changeB, List<String> services, List<String> cis, Instant overlapStart,
                                   Instant overlapEnd) {}

    public record ChangeCalendar(Instant from, Instant to, List<CalendarEntry> entries, List<FreezeDto> freezeWindows,
                                 List<CalendarConflict> conflicts) {}

    public record CorrelatedIncident(IncidentSummary incident, long minutesAfterStart, boolean linked) {}

    public record Correlation(Instant windowStart, Instant windowEnd, int hours, List<LinkedIncident> linked, List<CorrelatedIncident> correlated) {}

    public record ChangeMetrics(LocalDate from, LocalDate to, long created, Map<String, Long> byType, Map<String, Long> byStatus,
                                Map<String, Long> byRisk, long completed, long failed, long rolledBack, Double successRate, Double failureRate,
                                Double rollbackRate, Double emergencyRate, Double avgApprovalHours, Double avgLeadTimeHours, Double onTimeRate,
                                long changesFollowedByIncidents, long reviewsOutstanding) {}

    /** AI / deterministic change-risk analysis (wrapped in {@code AiResult}, labelled by source). */
    public record RiskAnalysis(Change.Risk riskLevel, int score, List<ChangeRisk.Factor> factors, List<String> recommendations,
                               List<SimilarChange> similarChanges, String basis) {}

    public record SimilarChange(String number, String title, ChangeStatus status, Change.Risk risk, Instant closedAt) {}
}

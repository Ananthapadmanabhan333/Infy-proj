package com.nexus360.incident;

import com.nexus360.sla.IncidentSla;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request/response records for the incident API. Shapes match frontend/src/lib/types.ts. */
public final class IncidentDtos {

    private IncidentDtos() {}

    public record SlaSnapshot(String policyName, boolean businessHoursOnly, IncidentSla.ClockStatus clockStatus,
                              IncidentSla.Health health, IncidentSla.Outcome outcome, Instant responseDueAt, Instant respondedAt,
                              boolean responseBreached, Instant resolutionDueAt, long remainingSeconds, double percentElapsed,
                              int escalationLevel) {}

    public record IncidentSummary(Long id, String number, String title, Priority priority, Level impact, Level urgency,
                                  IncidentStatus status, Category category, Long serviceId, String serviceName, Long departmentId,
                                  String departmentName, Long assigneeId, String assigneeName, Long requesterId, String requesterName,
                                  Long parentId, String parentNumber, boolean major, int childCount, Instant createdAt,
                                  Instant updatedAt, Instant resolvedAt, SlaSnapshot sla, boolean demo, Long teamId, String teamName,
                                  boolean simulated) {}

    public record LinkedIncident(Long id, String number, String title, IncidentStatus status, Priority priority) {}

    public record IncidentLinkDto(Long id, LinkType linkType, String direction, LinkedIncident incident) {}

    public record ArticleRef(Long id, String number, String title) {}

    public record IncidentDetail(Long id, String number, String title, Priority priority, Level impact, Level urgency,
                                 IncidentStatus status, Category category, Long serviceId, String serviceName, Long departmentId,
                                 String departmentName, Long assigneeId, String assigneeName, Long requesterId, String requesterName,
                                 Long parentId, String parentNumber, boolean major, int childCount, Instant createdAt,
                                 Instant updatedAt, Instant resolvedAt, SlaSnapshot sla, boolean demo,
                                 String description, ClassificationSource classificationSource, Double classificationConfidence,
                                 int affectedUsers, IncidentSource source, int escalationLevel, ResolutionCode resolutionCode,
                                 String resolutionNotes, String rootCause, ArticleRef resolutionArticle, Instant firstResponseAt,
                                 Instant closedAt, String resolvedByName, String createdByName, Double duplicateScore,
                                 List<IncidentSummary> children, List<IncidentLinkDto> links, List<IncidentStatus> allowedTransitions,
                                 List<EscalationDto> escalations, List<IncidentSummary> relatedSameService, Long teamId, String teamName,
                                 boolean simulated, Long correlationGroupId) {}

    /** {@code source}: SLA, MANUAL, WORKFLOW or AI. */
    public record EscalationDto(int level, String reason, String actorName, Instant at, String source) {}

    /** {@code kind}: HISTORY, COMMENT, SLA, ESCALATION, APPROVAL or RUNBOOK. */
    public record ActivityItem(String kind, Instant at, String actorName, String title, String detail) {}

    /** Tab counts: open = active statuses, inProgress = IN_PROGRESS, resolved = RESOLVED + CLOSED. */
    public record IncidentCounts(long all, long open, long inProgress, long resolved) {}

    public record CreateIncidentRequest(
            @NotBlank @Size(min = 5, max = 200) String title,
            @Size(max = 5000) String description,
            Long serviceId,
            Category category,
            Level impact,
            Level urgency,
            @Min(0) @Max(1_000_000) Integer affectedUsers,
            Long requesterId,
            IncidentSource source) {}

    public record Classification(Category category, Long serviceId, String serviceName, ClassificationSource source, Double confidence) {}

    public record DuplicateOutcome(boolean detected, String matchedIncidentNumber, Long parentId, String parentNumber,
                                   boolean parentCreated, double score) {}

    public record AssignmentOutcome(String departmentName, String assigneeName, String teamName) {}

    public record CreateIncidentResult(IncidentDetail incident, Classification classification, DuplicateOutcome duplicate,
                                       AssignmentOutcome assignment) {}

    public record UpdateIncidentRequest(
            @NotBlank @Size(min = 5, max = 200) String title,
            @Size(max = 5000) String description,
            @NotNull Category category,
            @NotNull Level impact,
            @NotNull Level urgency,
            Long serviceId,
            @Min(0) @Max(1_000_000) int affectedUsers) {}

    /** CLOSED from NEW/ASSIGNED cancels the incident and needs {@code comment} (the reason, at least 5 characters). */
    public record TransitionRequest(
            @NotNull IncidentStatus status,
            ResolutionCode resolutionCode,
            @Size(max = 5000) String resolutionNotes,
            @Size(max = 5000) String rootCause,
            Long resolutionArticleId,
            @Size(max = 2000) String comment) {}

    /**
     * {@code teamId} (Release 3) assigns through a team: the department defaults to the team's department and an assignee,
     * when given, must be a member. Without a team the team is derived from the assignee and department.
     */
    public record AssignRequest(Long departmentId, Long assigneeId, Long teamId) {}

    public record EscalateRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record MajorRequest(boolean major) {}

    public record ParentRequest(@NotNull Long parentId) {}

    public record LinkRequest(@NotNull Long targetId, @NotNull LinkType linkType) {}

    public record CommentRequest(@NotBlank @Size(max = 10000) String body, boolean internal) {}

    public record CommentDto(Long id, String body, boolean internal, boolean systemGenerated, Long authorId, String authorName,
                             Instant createdAt) {}

    public record AttachmentDto(Long id, String fileName, String contentType, long sizeBytes, String uploadedByName, Instant createdAt) {}

    public record HistoryDto(Long id, String eventType, String field, String oldValue, String newValue, String message,
                             String actorName, Instant createdAt) {}

    public record DuplicateCandidate(Long incidentId, String number, String title, IncidentStatus status, Priority priority,
                                     String serviceName, Instant createdAt, double score, Long parentId) {}

    public record PreviewDuplicatesRequest(@Size(max = 200) String title, @Size(max = 5000) String description, Long serviceId) {}

    public record SlaEventDto(Long id, String eventType, String details, Instant createdAt) {}

    public record SlaDetail(String policyName, boolean businessHoursOnly, IncidentSla.ClockStatus clockStatus,
                            IncidentSla.Health health, IncidentSla.Outcome outcome, Instant responseDueAt, Instant respondedAt,
                            boolean responseBreached, Instant resolutionDueAt, long remainingSeconds, double percentElapsed,
                            int escalationLevel, Instant startedAt, int responseTargetMinutes, int resolutionTargetMinutes,
                            int atRiskPercent, long totalPausedSeconds, List<SlaEventDto> events) {}
}

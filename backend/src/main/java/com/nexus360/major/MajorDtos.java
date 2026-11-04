package com.nexus360.major;

import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.incident.Priority;
import com.nexus360.statuspage.StatusUpdate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request and response shapes of major incident command and post-incident reviews. */
public final class MajorDtos {

    private MajorDtos() {}

    public record PersonRef(Long id, String name, String email) {}

    public record ServiceRef(Long id, String code, String name) {}

    // ------------------------------------------------------------------ major incident command

    /** Declare an incident a major incident (sets the incident's major flag) and open the command record. */
    public record DeclareRequest(@NotNull Long incidentId, Long commanderId, Long techLeadId, Long commsLeadId, @Size(max = 2000) String summary,
                                 @Size(max = 20) List<@NotNull Long> serviceIds, @Size(max = 500) @Pattern(regexp = "^$|^https?://.+",
            message = "must be an http(s) link") String bridgeUrl) {}

    /** {@code 0} clears a role; {@code null} leaves it unchanged. */
    public record RolesRequest(Long commanderId, Long techLeadId, Long commsLeadId) {}

    /** {@code null} fields are left unchanged; {@code serviceIds} replaces the impacted services. */
    public record UpdateRequest(@Size(max = 2000) String summary, @Size(max = 20000) String impactSummary, @Size(max = 50000) String investigationNotes,
                                @Size(max = 20000) String resolutionSummary, @Size(max = 500) @Pattern(regexp = "^$|^https?://.+",
            message = "must be an http(s) link") String bridgeUrl, @Size(max = 20) List<@NotNull Long> serviceIds) {}

    public record StatusRequest(@NotNull MajorIncident.Status status, @Size(max = 2000) String note) {}

    public record TimelineRequest(@NotNull MajorTimelineEntry.Type entryType, @NotBlank @Size(max = 4000) String body, Instant occurredAt) {}

    /** {@code publishToStatusPage}: also post it as a public status-page update (status page editors, the commander or comms lead). */
    public record StakeholderUpdateRequest(@NotNull StatusUpdate.Status status, @Size(max = 200) String title, @NotBlank @Size(max = 5000) String message,
                                           Boolean publishToStatusPage) {}

    public record TimelineDto(Long id, MajorTimelineEntry.Type entryType, String body, Long actorId, String actorName, Instant occurredAt,
                              boolean demo) {}

    public record StakeholderUpdateDto(Long id, StatusUpdate.Status status, String title, String message, boolean published, Long statusUpdateId,
                                       Instant publishedAt, String postedByName, Instant postedAt, boolean demo) {}

    public record ActionRequest(@NotBlank @Size(max = 200) String title, @Size(max = 2000) String description, ActionItem.Type actionType,
                                Long ownerId, LocalDate dueDate, ActionItem.Status status) {}

    public record ActionItemDto(Long id, String source, Long majorIncidentId, Long pirId, String title, String description, ActionItem.Type actionType,
                                Long ownerId, String ownerName, LocalDate dueDate, ActionItem.Status status, boolean overdue, Instant completedAt,
                                boolean demo, Instant createdAt, Instant updatedAt) {}

    public record AlertRef(Long id, String number, String title, String severity, String status) {}

    public record PirRef(Long id, String number, PostIncidentReview.Status status) {}

    public record MajorSummary(Long id, String number, MajorIncident.Status status, Long incidentId, String incidentNumber, String title,
                               Priority priority, IncidentStatus incidentStatus, PersonRef commander, PersonRef techLead, PersonRef commsLead,
                               Instant declaredAt, Instant resolvedAt, Instant closedAt, long durationMinutes, long openActions, PirRef pir,
                               boolean demo) {}

    public record MajorDetail(Long id, String number, MajorIncident.Status status, IncidentSummary incident, PersonRef commander, PersonRef techLead,
                              PersonRef commsLead, String summary, String impactSummary, String investigationNotes, String resolutionSummary,
                              String bridgeUrl, Instant declaredAt, String declaredByName, Instant resolvedAt, Instant closedAt, long durationMinutes,
                              List<ServiceRef> services, List<TimelineDto> timeline, List<StakeholderUpdateDto> updates, List<ActionItemDto> actionItems,
                              List<AlertRef> alerts, PirRef pir, List<MajorIncident.Status> allowedTransitions, boolean canCommand, boolean canPublish,
                              boolean demo, Instant createdAt, Instant updatedAt) {}

    // ------------------------------------------------------------------ post-incident reviews

    public record CreatePirRequest(@NotNull Long incidentId, Long ownerId, @Size(max = 200) String title) {}

    /** {@code null} fields are left unchanged. */
    public record PirUpdateRequest(@Size(min = 5, max = 200) String title, Long ownerId, @Size(max = 20000) String summary,
                                   @Size(max = 20000) String rootCause, @Size(max = 20) List<@NotBlank @Size(max = 500) String> contributingFactors,
                                   @Size(max = 20000) String impact, @Size(max = 20000) String detectionGaps, @Size(max = 20000) String responseAnalysis,
                                   @Size(max = 20000) String lessonsLearned) {}

    public record PirSummary(Long id, String number, String title, PostIncidentReview.Status status, Long incidentId, String incidentNumber,
                             String incidentTitle, Long majorIncidentId, String majorIncidentNumber, PersonRef owner, long openActions, long actionCount,
                             Instant submittedAt, Instant approvedAt, boolean demo, Instant createdAt, Instant updatedAt) {}

    public record PirDetail(Long id, String number, String title, PostIncidentReview.Status status, IncidentSummary incident, Long majorIncidentId,
                            String majorIncidentNumber, PersonRef owner, String summary, String rootCause, List<String> contributingFactors,
                            String impact, String detectionGaps, String responseAnalysis, String lessonsLearned,
                            List<PostIncidentReview.TimelineItem> timeline, Map<String, Object> metrics, Instant timelineBuiltAt,
                            List<ActionItemDto> actions, Long approvalId, String approvalStatus, Instant submittedAt, Instant approvedAt,
                            String approvedByName, String reviewComment, boolean canEdit, boolean canSubmit, boolean demo, Instant createdAt,
                            Instant updatedAt) {}
}

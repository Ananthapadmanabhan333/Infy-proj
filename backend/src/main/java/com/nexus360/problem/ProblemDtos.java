package com.nexus360.problem;

import com.nexus360.incident.Category;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ProblemDtos {

    private ProblemDtos() {}

    public record ServiceRef(Long id, String code, String name) {}

    public record ChangeRef(Long id, String number, String title, String status, String type) {}

    public record ProblemSummary(Long id, String number, String title, ProblemStatus status, Priority priority, Category category, Long ownerId,
                                 String ownerName, Long teamId, String teamName, boolean knownError, long incidentCount, long openActionCount,
                                 List<ServiceRef> services, Instant createdAt, Instant updatedAt, boolean demo) {}

    public record ActionDto(Long id, String title, String description, ProblemAction.Type actionType, Long ownerId, String ownerName,
                            LocalDate dueDate, ProblemAction.Status status, boolean overdue, Instant completedAt, Instant createdAt) {}

    public record HistoryDto(Instant at, String actorName, String action, String fromValue, String toValue, String note) {}

    public record ProblemDetail(Long id, String number, String title, String description, ProblemStatus status, Priority priority,
                                Category category, Long ownerId, String ownerName, Long teamId, String teamName, Long departmentId,
                                String departmentName, String rootCause, String workaround, String permanentFix, boolean knownError,
                                Instant knownErrorAt, Long kbArticleId, String kbArticleNumber, String kbArticleStatus, List<String> affectedCis,
                                String closureNote, Instant rootCauseAt, Instant resolvedAt, Instant closedAt, String createdByName,
                                List<ServiceRef> services, List<IncidentSummary> incidents, List<ActionDto> actions, List<ChangeRef> changes,
                                List<ProblemStatus> allowedTransitions, Instant createdAt, Instant updatedAt, boolean demo,
                                List<com.nexus360.cmdb.CmdbDtos.CiRef> cis) {}

    /** Create/update. On update a {@code null} list leaves it unchanged; {@code incidentIds} is only used on create. */
    public record ProblemRequest(
            @NotBlank @Size(min = 5, max = 200) String title,
            @Size(max = 10_000) String description,
            Priority priority,
            Category category,
            Long ownerId,
            Long teamId,
            @Size(max = 20) List<Long> serviceIds,
            @Size(max = 30) List<@NotBlank @Size(max = 120) String> affectedCis,
            @Size(max = 10_000) String rootCause,
            @Size(max = 10_000) String workaround,
            @Size(max = 10_000) String permanentFix,
            @Size(max = 200) List<Long> incidentIds,
            @Size(max = 30) List<Long> ciIds) {

        /** Release 2/BE-2 shape without CI links ({@code ciIds} null = links follow {@code affectedCis} names). */
        public ProblemRequest(String title, String description, Priority priority, Category category, Long ownerId, Long teamId, List<Long> serviceIds,
                              List<String> affectedCis, String rootCause, String workaround, String permanentFix, List<Long> incidentIds) {
            this(title, description, priority, category, ownerId, teamId, serviceIds, affectedCis, rootCause, workaround, permanentFix, incidentIds, null);
        }
    }

    /**
     * {@code note}: required for cancellation (closing a NEW/INVESTIGATING problem). Moving to ROOT_CAUSE_IDENTIFIED needs a
     * root cause, KNOWN_ERROR a workaround, RESOLVED a permanent fix (given here or stored). {@code publishKnownError}: also
     * create the knowledge-base KNOWN_ERROR article.
     */
    public record TransitionRequest(@NotNull ProblemStatus status, @Size(max = 1000) String note, @Size(max = 10_000) String rootCause,
                                    @Size(max = 10_000) String workaround, @Size(max = 10_000) String permanentFix, Boolean publishKnownError) {}

    public record LinkRequest(@NotEmpty @Size(max = 200) List<Long> incidentIds) {}

    public record ActionRequest(@NotBlank @Size(max = 200) String title, @Size(max = 2000) String description, ProblemAction.Type actionType,
                                Long ownerId, LocalDate dueDate, ProblemAction.Status status) {}

    /** {@code publish}: publish immediately (knowledge publishers) or submit for review (other staff); otherwise a draft. */
    public record KnownErrorArticleRequest(Boolean publish) {}

    public record ClusterIncident(Long id, String number, String title, String status, Instant createdAt, double similarity) {}

    public record RecurringCluster(String key, Long serviceId, String serviceName, Category category, int size, double cohesion,
                                   Instant firstSeen, Instant lastSeen, String suggestedTitle, List<ClusterIncident> incidents) {}

    public record FromClusterRequest(@NotEmpty @Size(max = 200) List<Long> incidentIds, @Size(min = 5, max = 200) String title,
                                     Priority priority, Long ownerId) {}

    public record ServiceCount(Long serviceId, String serviceName, long problems, long incidents) {}

    public record ProblemAnalytics(LocalDate from, LocalDate to, long total, long open, Map<String, Long> byStatus, Map<String, Long> byPriority,
                                   long knownErrors, long createdInPeriod, long resolvedInPeriod, Double avgHoursToRootCause,
                                   Double avgHoursToResolve, long linkedIncidents, long recurrencesAfterKnownError, long overdueActions,
                                   long recurringClusters, List<ServiceCount> topServices) {}

    /** AI / deterministic root-cause suggestion for a problem (wrapped in {@code AiResult}, labelled by source). */
    public record RootCauseSuggestion(List<com.nexus360.ai.AiDtos.Hypothesis> hypotheses, List<String> commonFactors, String suggestedWorkaround,
                                      int incidentsAnalysed, String basis) {}
}

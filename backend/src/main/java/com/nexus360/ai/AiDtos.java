package com.nexus360.ai;

import com.nexus360.incident.Category;
import com.nexus360.incident.IncidentDtos.DuplicateCandidate;
import com.nexus360.incident.Level;
import com.nexus360.incident.Priority;
import com.nexus360.knowledge.KnowledgeDtos.ArticleSummary;
import com.nexus360.notification.Notification.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AiDtos {

    private AiDtos() {}

    public enum Source { LLM, FALLBACK }

    /** Every AI response carries its provenance; the UI always shows {@code notice}. */
    public record AiResult<T>(Source source, String provider, String model, Instant generatedAt, long latencyMs, String notice,
                              String fallbackReason, T data) {}

    public record AiStatus(String provider, String model, boolean llmConfigured, boolean llmEnabled, boolean fallbackAvailable,
                           String lastError) {}

    public record ClassificationSuggestion(Category category, Long serviceId, String serviceName, Level impact, Level urgency,
                                           Priority priority, double confidence, String rationale) {}

    public record IncidentSummaryAi(String summary, List<String> keyFacts, List<String> nextSteps) {}

    public record DuplicateSuggestions(List<DuplicateCandidate> candidates, String explanation) {}

    public record KnowledgeRecommendations(List<ArticleSummary> articles, String explanation) {}

    public record Hypothesis(String cause, String likelihood, List<String> evidence, List<String> suggestedChecks) {}

    public record RootCauseAnalysis(List<Hypothesis> hypotheses) {}

    /** {@code confidence}: HIGH, MEDIUM or LOW — how much data stands behind the insight. */
    public record Insight(String title, String detail, Severity severity, String metric, String confidence) {}

    public record HourCount(int hour, long count) {}

    /** {@code hourly}: incidents created per hour of day over the last 30 days (default calendar time zone), always from the database. */
    public record OperationalInsights(List<Insight> insights, List<HourCount> hourly) {}

    public record RunbookRef(Long id, String title, boolean requiresApproval) {}

    /** A resolved incident that supports the suggestion, with its text/service similarity (0–1). */
    public record MatchedIncident(Long incidentId, String number, String title, String rootCause, double similarity) {}

    /**
     * {@code confidence} 0–100. For the deterministic engine it is computed from the similarity of the matched resolved
     * incidents, the relevance of the matched articles and how well the runbook fits — never a fixed value.
     */
    public record ResolutionSuggestion(String likelyCause, int confidence, List<String> steps, RunbookRef runbook,
                                       List<ArticleSummary> articles, List<MatchedIncident> matchedIncidents, String basis) {}

    public record WorkflowSuggestionRequest(@NotBlank @Size(min = 5, max = 1000) String goal) {}

    /** A proposed workflow, validated against the workflow model; never saved automatically. */
    public record WorkflowSuggestion(String name, String description, com.nexus360.workflow.WorkflowModel.Trigger triggerEvent,
                                     com.nexus360.workflow.WorkflowModel.Definition definition, String explanation) {}

    /** Recent use of SA AI by the current user ("Recent Analyses"). */
    public record InteractionDto(Long id, String feature, String subject, Source source, Instant createdAt) {}

    public record DataPoint(String label, double value) {}

    public record AskResponse(String answer, List<DataPoint> data, String basis) {}

    public record AskRequest(@NotBlank @Size(max = 500) String question) {}

    public enum ActionType { SET_PRIORITY, LINK_DUPLICATE, START_RUNBOOK, ESCALATE }

    public record AiActionRequest(@NotNull Long incidentId, @NotNull ActionType actionType, Map<String, String> params,
                                  @NotBlank @Size(max = 1000) String rationale) {}
}

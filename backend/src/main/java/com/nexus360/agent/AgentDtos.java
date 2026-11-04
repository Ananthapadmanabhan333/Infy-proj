package com.nexus360.agent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Contracts of SA Agentic AI ({@code /api/agents}). */
public final class AgentDtos {

    private AgentDtos() {}

    public enum AgentCode { INCIDENT, ROOT_CAUSE, CHANGE_RISK, KNOWLEDGE, INFRA_INVESTIGATION, RESOLUTION }

    public enum RunType { INVESTIGATE_INCIDENT, ASSESS_CHANGE, ANSWER_QUESTION }

    /** State-changing proposals; never applied by an agent (approval request or a prefilled link to the target UI). */
    public enum ActionKind { ASSIGN_TEAM, CHANGE_PRIORITY, LINK_PROBLEM, PUBLISH_KB_DRAFT, RUN_PLAYBOOK, DECLARE_MAJOR }

    public enum ToolStatus { OK, EMPTY, DENIED, NOT_FOUND, DISALLOWED, INVALID, ERROR, TIMEOUT }

    // ------------------------------------------------------------------ requests

    public record StartRunRequest(@NotNull RunType runType, Long incidentId, Long changeId, @Size(max = 1000) String question,
                                  @Min(5) @Max(600) Integer timeoutSeconds) {}

    public record FeedbackRequest(@NotNull Boolean helpful, @Size(max = 1000) String comment) {}

    public record DismissRequest(@NotBlank @Size(min = 3, max = 1000) String reason) {}

    public record PolicyRequest(@NotNull Boolean enabled, @NotNull @Size(max = 30) List<@NotBlank @Size(max = 40) String> allowedTools,
                                @NotNull @Min(1) @Max(20) Integer maxSteps, @NotNull @Min(1) @Max(300) Integer timeoutSeconds,
                                @NotNull @Size(max = 20) List<@NotBlank @Size(max = 20) String> allowedRoles,
                                @NotNull @Size(max = 10) List<ActionKind> approvalActionKinds, @Valid DataScopes dataScopes) {}

    /** {@code telemetryMinutes} window of telemetry tools (5–1440, 60); {@code historyDays} of history tools (1–365, 180). */
    public record DataScopes(@Min(5) @Max(1440) Integer telemetryMinutes, @Min(1) @Max(365) Integer historyDays) {}

    public record Expected(@Size(max = 20) String category, @Size(max = 10) String priority, @Size(max = 200) String rootCauseComponent,
                           @Size(max = 20) List<Long> kbArticleIds, @Size(max = 20) List<@NotBlank @Size(max = 80) String> keywords) {}

    public record EvalCaseRequest(@NotBlank @Size(min = 3, max = 160) String name, @NotNull AgentCode agentCode, @NotNull RunType runType,
                                  Long incidentId, Long changeId, @Size(max = 1000) String question, @NotNull @Valid Expected expected, Boolean enabled) {}

    public record EvalRunRequest(AgentCode agentCode, @Size(max = 100) List<Long> caseIds) {}

    // ------------------------------------------------------------------ responses

    public record ToolDto(String name, String description, Map<String, String> args, List<String> roles) {}

    public record PolicyDto(AgentCode agentCode, boolean enabled, List<String> allowedTools, int maxSteps, int timeoutSeconds, List<String> allowedRoles,
                            List<ActionKind> approvalActionKinds, Map<String, Object> dataScopes, String updatedByName, Instant updatedAt) {}

    public record AgentDto(AgentCode code, String name, String description, String version, List<String> declaredTools, List<RunType> runTypes,
                           Map<String, Object> outputSchema, PolicyDto policy) {}

    public record VersionDto(Long id, AgentCode agentCode, String version, String promptSha256, String prompt, Map<String, Object> outputSchema,
                             List<String> tools, Instant firstSeenAt) {}

    public record FeedbackSummary(long helpful, long notHelpful, Boolean mine) {}

    public record RunDto(Long id, RunType runType, String status, String subjectType, Long subjectId, String subjectNumber, String question, String source,
                         Double confidence, String summary, Map<String, Object> agentVersions, int timeoutSeconds, int stepCount, int toolCallCount,
                         int tokensIn, int tokensOut, String error, boolean cancelRequested, String cancelledByName, Long requestedById, String requestedByName,
                         Long evalRunId, boolean simulated, boolean demo, Instant queuedAt, Instant startedAt, Instant finishedAt, Long durationMs,
                         Instant createdAt, FeedbackSummary feedback) {}

    public record ToolCallDto(Long id, int seq, String tool, Map<String, Object> args, String status, JsonNode result, int resultSize, boolean truncated,
                              String message, String requestedBy, long durationMs, Instant createdAt) {}

    public record StepDto(Long id, int seq, int stage, AgentCode agentCode, String agentVersion, String status, JsonNode input, JsonNode output,
                          String source, String fallbackReason, Double confidence, JsonNode evidence, int llmTurns, int tokensIn, int tokensOut, String error,
                          Instant startedAt, Instant finishedAt, Long durationMs, List<ToolCallDto> toolCalls) {}

    public record ActionDto(Long id, Long runId, Long stepId, AgentCode agentCode, ActionKind kind, String targetType, Long targetId,
                            Map<String, Object> params, String title, String rationale, boolean requiresApproval, String link, String status,
                            Long approvalId, String decidedByName, Instant decidedAt, String decisionComment, String result, Instant createdAt) {}

    public record FeedbackDto(Long id, Long userId, String userName, boolean helpful, String comment, Instant createdAt, Instant updatedAt) {}

    public record RunDetail(RunDto run, JsonNode input, JsonNode output, List<StepDto> steps, List<ActionDto> actions, List<FeedbackDto> feedback) {}

    public record EvalCaseDto(Long id, String name, AgentCode agentCode, RunType runType, String subjectType, Long subjectId, String question,
                              Map<String, Object> expected, boolean enabled, boolean demo, String createdByName, Instant createdAt, Instant updatedAt) {}

    public record EvalRunDto(Long id, AgentCode agentCode, String status, Map<String, Object> agentVersions, int caseCount, int passed, int failed,
                             Double score, JsonNode results, String error, String startedByName, Instant startedAt, Instant finishedAt, Instant createdAt) {}

    // ------------------------------------------------------------------ agent outputs (validated against the per-agent JSON schema)

    public record EvidenceRef(String type, Long id, String label) {}

    public record ProposedAction(String kind, Map<String, String> params, String rationale) {}

    public record TriageOutput(String summary, String category, String recommendedCategory, String priority, String recommendedPriority,
                               String priorityReason, List<String> keyFacts, Double slaBreachProbability, List<EvidenceRef> evidence, double confidence,
                               List<ProposedAction> proposedActions) {}

    public record Hypothesis(String cause, String component, Long componentCiId, double likelihood, List<String> supporting, List<EvidenceRef> evidence,
                             List<String> suggestedChecks) {}

    public record RootCauseOutput(String summary, List<Hypothesis> hypotheses, List<EvidenceRef> evidence, double confidence,
                                  List<ProposedAction> proposedActions) {}

    public record Finding(String title, String detail, String severity, List<EvidenceRef> evidence) {}

    public record InfraOutput(String summary, List<Finding> findings, List<String> impactedComponents, List<EvidenceRef> evidence, double confidence,
                              List<ProposedAction> proposedActions) {}

    public record Citation(Long articleId, String number, String title, double relevance, String excerpt) {}

    public record KnowledgeOutput(String answer, List<Citation> citations, List<EvidenceRef> evidence, double confidence,
                                  List<ProposedAction> proposedActions) {}

    public record RunbookRef(Long id, String title, boolean requiresApproval, int score) {}

    public record ResolutionOutput(String summary, List<String> steps, RunbookRef runbook, List<EvidenceRef> evidence, double confidence,
                                   List<ProposedAction> proposedActions) {}

    public record ChangeRiskOutput(String summary, String riskLevel, int score, List<String> factors, List<String> recommendations,
                                   List<String> impactedServices, List<EvidenceRef> evidence, double confidence, List<ProposedAction> proposedActions) {}

    // ------------------------------------------------------------------ LLM tool-use turn (structured output)

    /** One turn of the bounded tool-use loop: call one allowed tool, or answer with the agent's output as a JSON object string. */
    public static class AgentTurn {
        @JsonPropertyDescription("TOOL to call one tool, FINAL to give the answer")
        @Pattern(regexp = "TOOL|FINAL")
        public String action;
        @JsonPropertyDescription("TOOL only: the tool name from the tool list")
        public String tool;
        @JsonPropertyDescription("TOOL only: the tool arguments")
        public List<TurnArg> args;
        @JsonPropertyDescription("FINAL only: the answer, a JSON object that matches the output schema exactly")
        public String outputJson;

        public static class TurnArg {
            public String name;
            public String value;

            public TurnArg() {}

            public TurnArg(String name, String value) {
                this.name = name;
                this.value = value;
            }
        }

        public AgentTurn() {}

        public static AgentTurn tool(String tool, Map<String, String> args) {
            AgentTurn t = new AgentTurn();
            t.action = "TOOL";
            t.tool = tool;
            t.args = args.entrySet().stream().map(e -> new TurnArg(e.getKey(), e.getValue())).toList();
            return t;
        }

        public static AgentTurn fin(String outputJson) {
            AgentTurn t = new AgentTurn();
            t.action = "FINAL";
            t.outputJson = outputJson;
            return t;
        }
    }
}

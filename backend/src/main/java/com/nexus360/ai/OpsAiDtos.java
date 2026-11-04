package com.nexus360.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.time.Instant;
import java.util.List;

/**
 * Release 3 SA AI features (problem correlation, CMDB impact analysis, runbook recommendations, predictive SLA risk, trend analysis).
 * Every response is wrapped in {@link AiDtos.AiResult} ({@code source} LLM or FALLBACK with notice and reason). Scores, probabilities
 * and counts are always computed deterministically from the database; the LLM may only select among and explain the computed
 * candidates. The {@code *Out} classes are the structured-output schemas sent to the model.
 */
public final class OpsAiDtos {

    private OpsAiDtos() {}

    // ------------------------------------------------------------------ problem correlation

    public record CorrelatedIncident(Long incidentId, String number, String title, String status, Instant createdAt, double score, List<String> reasons) {}

    public record CorrelatedChange(Long changeId, String number, String title, String status, Instant startedAt, long incidentsWithin48h) {}

    public record AlertGroupRef(String groupKey, String title, long alerts, String maxSeverity) {}

    public record ProblemCorrelation(Long problemId, String number, String title, List<CorrelatedIncident> candidates, List<CorrelatedChange> changes,
                                     List<AlertGroupRef> alertGroups, String summary, String basis) {}

    public static class ProblemCorrelationOut {
        @JsonPropertyDescription("Incident numbers from the candidate list that are most likely caused by the same problem, most likely first")
        public List<String> relatedIncidentNumbers;
        @JsonPropertyDescription("Two or three sentences explaining the correlation, grounded in the data")
        public String summary;
    }

    // ------------------------------------------------------------------ CMDB impact

    public record ImpactedServiceRef(Long serviceId, String code, String name, int tier, String status) {}

    public record CmdbImpact(Long ciId, String ciName, String ciType, String criticality, String riskLevel, List<ImpactedServiceRef> impactedServices,
                             int upstreamCount, int downstreamCount, List<String> criticalUpstream, long openIncidents, long openAlerts, String summary,
                             List<String> recommendations, boolean truncated, String basis) {}

    public static class CmdbImpactOut {
        @JsonPropertyDescription("Two or three sentences on what fails if this configuration item fails, grounded in the data")
        public String summary;
        @JsonPropertyDescription("Concrete recommendations for operators and change planners, in plain language")
        public List<String> recommendations;
    }

    // ------------------------------------------------------------------ runbooks

    public record RunbookRecommendation(Long runbookId, String title, int score, boolean requiresApproval, Integer estimatedMinutes,
                                        List<String> reasons) {}

    public record RunbookRecommendations(Long incidentId, String incidentNumber, List<RunbookRecommendation> recommendations, String rationale,
                                         String basis) {}

    public static class RunbookOut {
        @JsonPropertyDescription("Ids of the candidate runbooks worth running for this incident, best first (only ids from the candidate list)")
        public List<Long> runbookIds;
        @JsonPropertyDescription("One or two sentences explaining the choice")
        public String rationale;
    }

    // ------------------------------------------------------------------ predictive SLA risk

    /**
     * {@code probability} (0–1) is computed, never generated: the share of historical resolution clocks of the same priority that were
     * still running at the same elapsed share and went on to breach ({@code historicalBreaches} of {@code sampleSize}), smoothed toward
     * the elapsed share when fewer than 5 comparable clocks exist; 1 when already breached. {@code probabilityLabel} says so.
     */
    public record SlaRisk(Long incidentId, String number, String title, String priority, String policyName, String clockStatus, double elapsedPercent,
                          long remainingMinutes, Instant resolutionDueAt, Double probability, String riskLevel, String probabilityLabel, int sampleSize,
                          int historicalBreaches, int historyDays, String method, List<String> factors, List<String> recommendations) {}

    public record SlaRiskOverview(Instant generatedAt, List<SlaRisk> incidents, String method) {}

    public static class SlaRiskOut {
        @JsonPropertyDescription("Concrete recommendations to avoid the SLA breach, in plain language")
        public List<String> recommendations;
    }

    // ------------------------------------------------------------------ trends

    public record Trend(String metric, String dimension, double current, double previous, Double changePercent, String direction, Double zScore,
                        boolean significant) {}

    public record TrendAnalysis(String from, String to, String previousFrom, String previousTo, int days, List<Trend> trends, List<String> highlights,
                                String narrative, String basis) {}

    public static class TrendOut {
        @JsonPropertyDescription("The most important changes as short sentences, each citing the numbers from the data")
        public List<String> highlights;
        @JsonPropertyDescription("Two or three sentences summarising the trend picture")
        public String narrative;
    }
}

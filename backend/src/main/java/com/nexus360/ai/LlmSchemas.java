package com.nexus360.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Structured-output schemas sent to the LLM. Values are validated and mapped to API DTOs by {@link AiService};
 * the model can only choose among candidates the server retrieved, never invent records.
 */
public final class LlmSchemas {

    private LlmSchemas() {}

    public static class Classification {
        @JsonPropertyDescription("One of: APPLICATION, NETWORK, HARDWARE, ACCESS, DATABASE, EMAIL, SECURITY, FACILITIES, OTHER")
        public String category;
        @JsonPropertyDescription("Code of the affected business service from the provided list, or NONE")
        public String serviceCode;
        @JsonPropertyDescription("Business impact: HIGH, MEDIUM or LOW")
        public String impact;
        @JsonPropertyDescription("Urgency: HIGH, MEDIUM or LOW")
        public String urgency;
        @JsonPropertyDescription("Confidence between 0 and 1")
        public double confidence;
        @JsonPropertyDescription("One or two sentences explaining the classification")
        public String rationale;
    }

    public static class Summary {
        @JsonPropertyDescription("Three to five sentence summary for an operations manager")
        public String summary;
        @JsonPropertyDescription("Key facts, each a short sentence grounded in the incident data")
        public List<String> keyFacts;
        @JsonPropertyDescription("Concrete recommended next steps for the support team")
        public List<String> nextSteps;
    }

    public static class RankedItem {
        @JsonPropertyDescription("Reference of a candidate exactly as given, e.g. INC-7012 or KB-1004")
        public String reference;
        @JsonPropertyDescription("Relevance between 0 and 1")
        public double relevance;
    }

    public static class Ranking {
        @JsonPropertyDescription("Relevant candidates only, most relevant first")
        public List<RankedItem> items;
        @JsonPropertyDescription("Short explanation of why these candidates match")
        public String explanation;
    }

    public static class Hypothesis {
        @JsonPropertyDescription("Probable root cause")
        public String cause;
        @JsonPropertyDescription("HIGH, MEDIUM or LOW")
        public String likelihood;
        @JsonPropertyDescription("Evidence from the provided data supporting this hypothesis")
        public List<String> evidence;
        @JsonPropertyDescription("Checks an engineer should run to confirm or rule it out")
        public List<String> suggestedChecks;
    }

    public static class RootCause {
        @JsonPropertyDescription("One to four hypotheses, most likely first")
        public List<Hypothesis> hypotheses;
    }

    public static class Insight {
        public String title;
        public String detail;
        @JsonPropertyDescription("INFO, SUCCESS, WARNING or CRITICAL")
        public String severity;
        @JsonPropertyDescription("Headline number from the data, e.g. '+35%' or '3', or an empty string")
        public String metric;
        @JsonPropertyDescription("HIGH, MEDIUM or LOW: how strongly the provided data supports this insight")
        public String confidence;
    }

    public static class Resolution {
        @JsonPropertyDescription("Most likely cause of the incident, grounded in the similar resolved incidents and articles")
        public String likelyCause;
        @JsonPropertyDescription("Confidence from 0 to 100 that the suggested resolution applies")
        public int confidence;
        @JsonPropertyDescription("Ordered resolution steps for the engineer")
        public List<String> steps;
        @JsonPropertyDescription("Reference of the best runbook exactly as given (e.g. RB-12), or NONE")
        public String runbookReference;
        @JsonPropertyDescription("References of helpful articles exactly as given (e.g. KB-1001), most relevant first")
        public List<String> articleReferences;
    }

    public static class WorkflowRule {
        @JsonPropertyDescription("Condition field name from the provided list")
        public String field;
        @JsonPropertyDescription("EQUALS, NOT_EQUALS, IN, CONTAINS, GREATER_THAN or LESS_THAN")
        public String operator;
        @JsonPropertyDescription("Value to compare with; comma-separated for IN")
        public String value;
    }

    public static class WorkflowParam {
        public String key;
        public String value;
    }

    public static class WorkflowAction {
        @JsonPropertyDescription("Action type from the provided list")
        public String type;
        @JsonPropertyDescription("Parameters of the action as key/value pairs")
        public List<WorkflowParam> params;
    }

    public static class WorkflowSuggestion {
        @JsonPropertyDescription("Short workflow name")
        public String name;
        @JsonPropertyDescription("One-sentence description")
        public String description;
        @JsonPropertyDescription("Trigger from the provided list")
        public String triggerEvent;
        @JsonPropertyDescription("ALL or ANY")
        public String match;
        public List<WorkflowRule> rules;
        @JsonPropertyDescription("Actions when the conditions match (at least one)")
        public List<WorkflowAction> actions;
        @JsonPropertyDescription("Actions when the conditions do not match (usually empty)")
        public List<WorkflowAction> elseActions;
        @JsonPropertyDescription("Minutes between runs for a SCHEDULED trigger (at least 15), otherwise 0")
        public int intervalMinutes;
        @JsonPropertyDescription("Short explanation of how the workflow achieves the goal")
        public String explanation;
    }

    public static class Insights {
        @JsonPropertyDescription("Two to five operational insights grounded in the provided metrics")
        public List<Insight> insights;
    }

    public static class DataPoint {
        public String label;
        public double value;
    }

    public static class Answer {
        @JsonPropertyDescription("Direct answer to the question using only the provided data")
        public String answer;
        @JsonPropertyDescription("Numbers supporting the answer, copied from the provided data")
        public List<DataPoint> data;
        @JsonPropertyDescription("Which parts of the provided data the answer is based on")
        public String basis;
    }
}

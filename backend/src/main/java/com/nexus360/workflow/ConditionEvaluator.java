package com.nexus360.workflow;

import com.nexus360.workflow.WorkflowModel.ConditionRule;
import com.nexus360.workflow.WorkflowModel.Conditions;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure condition evaluation against a map of facts (all values compared as text, numbers for GREATER/LESS). */
public final class ConditionEvaluator {

    public record RuleResult(ConditionRule rule, boolean matched, String actual) {}

    public record Evaluation(boolean matched, List<RuleResult> results) {}

    private ConditionEvaluator() {}

    public static Evaluation evaluate(Conditions conditions, Map<String, String> facts) {
        List<ConditionRule> rules = conditions == null || conditions.rules() == null ? List.of() : conditions.rules();
        List<RuleResult> results = rules.stream().map(r -> new RuleResult(r, test(r, facts.get(r.field())), facts.get(r.field()))).toList();
        if (results.isEmpty()) {
            return new Evaluation(true, results);
        }
        boolean any = conditions != null && "ANY".equalsIgnoreCase(conditions.match());
        boolean matched = any ? results.stream().anyMatch(RuleResult::matched) : results.stream().allMatch(RuleResult::matched);
        return new Evaluation(matched, results);
    }

    static boolean test(ConditionRule rule, String actual) {
        String expected = rule.value() == null ? "" : rule.value().trim();
        String value = actual == null ? "" : actual.trim();
        return switch (rule.operator()) {
            case EQUALS -> value.equalsIgnoreCase(expected);
            case NOT_EQUALS -> !value.equalsIgnoreCase(expected);
            case IN -> Arrays.stream(expected.split(",")).map(String::trim).anyMatch(value::equalsIgnoreCase);
            case CONTAINS -> !expected.isEmpty() && value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
            case GREATER_THAN -> compare(value, expected) > 0;
            case LESS_THAN -> compare(value, expected) < 0;
        };
    }

    private static int compare(String actual, String expected) {
        try {
            return Double.compare(Double.parseDouble(actual), Double.parseDouble(expected));
        } catch (NumberFormatException e) {
            return 0; // non-numeric comparisons never match
        }
    }
}

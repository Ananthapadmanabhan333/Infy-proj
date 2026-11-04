package com.nexus360.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus360.workflow.WorkflowModel.ConditionRule;
import com.nexus360.workflow.WorkflowModel.Conditions;
import com.nexus360.workflow.WorkflowModel.Operator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {

    private final Map<String, String> facts = Map.of("priority", "CRITICAL", "serviceCode", "PAYROLL", "title", "Payroll portal down",
            "affectedUsers", "28", "major", "true");

    @Test
    void allRulesMustMatch() {
        Conditions all = new Conditions("ALL", List.of(new ConditionRule("priority", Operator.IN, "CRITICAL,HIGH"),
                new ConditionRule("affectedUsers", Operator.GREATER_THAN, "10"), new ConditionRule("title", Operator.CONTAINS, "portal")));
        assertThat(ConditionEvaluator.evaluate(all, facts).matched()).isTrue();

        Conditions failing = new Conditions("ALL", List.of(new ConditionRule("priority", Operator.EQUALS, "CRITICAL"),
                new ConditionRule("serviceCode", Operator.NOT_EQUALS, "PAYROLL")));
        ConditionEvaluator.Evaluation e = ConditionEvaluator.evaluate(failing, facts);
        assertThat(e.matched()).isFalse();
        assertThat(e.results()).extracting(ConditionEvaluator.RuleResult::matched).containsExactly(true, false);
    }

    @Test
    void anyRuleIsEnoughAndEmptyMatchesEverything() {
        Conditions any = new Conditions("ANY", List.of(new ConditionRule("priority", Operator.EQUALS, "LOW"),
                new ConditionRule("major", Operator.EQUALS, "TRUE")));
        assertThat(ConditionEvaluator.evaluate(any, facts).matched()).isTrue();
        assertThat(ConditionEvaluator.evaluate(new Conditions("ALL", List.of()), facts).matched()).isTrue();
    }

    @Test
    void numericComparisonsIgnoreNonNumbers() {
        assertThat(ConditionEvaluator.test(new ConditionRule("title", Operator.LESS_THAN, "5"), "abc")).isFalse();
        assertThat(ConditionEvaluator.test(new ConditionRule("affectedUsers", Operator.LESS_THAN, "50"), "28")).isTrue();
    }
}

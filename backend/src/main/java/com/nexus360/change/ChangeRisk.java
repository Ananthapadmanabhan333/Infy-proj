package com.nexus360.change;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.common.ApiException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Change risk assessment: a fixed questionnaire (answers stored on the change) plus factors computed from the records —
 * service tier, number of services, business-hours window, failed changes on the same services in the last 90 days and
 * the change type. Score 0–4 LOW, 5–9 MEDIUM, 10+ HIGH. Unanswered questions count with their middle option, so an
 * unassessed change is never scored as risk-free. STANDARD changes (pre-approved templates) are LOW by definition.
 */
public final class ChangeRisk {

    public record Option(String value, String label, int points) {}

    public record Question(String key, String text, List<Option> options) {}

    /** One contribution to the score ({@code source}: ANSWER or COMPUTED). */
    public record Factor(String key, String label, int points, String source) {}

    public record Assessment(Change.Risk risk, int score, List<Factor> factors) {}

    public static final List<Question> QUESTIONS = List.of(
            new Question("userImpact", "Will users notice the change while it is implemented?", List.of(
                    new Option("NONE", "No", 0), new Option("SOME", "Some users", 2), new Option("ALL", "All users of the service", 4))),
            new Question("tested", "Was the change tested in a non-production environment?", List.of(
                    new Option("YES", "Yes, fully", 0), new Option("PARTIAL", "Partly", 2), new Option("NO", "No", 4))),
            new Question("rollbackTested", "Has the rollback been tested?", List.of(
                    new Option("YES", "Yes", 0), new Option("NO", "No", 2), new Option("NOT_POSSIBLE", "Rollback is not possible", 4))),
            new Question("downtime", "Expected service downtime", List.of(
                    new Option("NONE", "None", 0), new Option("MINUTES", "Minutes", 1), new Option("HOURS", "Hours", 3))),
            new Question("complexity", "Technical complexity", List.of(
                    new Option("LOW", "Low", 0), new Option("MEDIUM", "Medium", 2), new Option("HIGH", "High", 3))),
            new Question("experience", "Has the team implemented this kind of change before?", List.of(
                    new Option("YES", "Yes", 0), new Option("NO", "No", 2))));

    public static final int MEDIUM_FROM = 5;
    public static final int HIGH_FROM = 10;

    private ChangeRisk() {}

    /** Validates answers: known questions and options only. */
    public static Map<String, String> validate(Map<String, String> answers) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (answers == null) {
            return clean;
        }
        answers.forEach((key, value) -> {
            Question q = QUESTIONS.stream().filter(x -> x.key().equals(key)).findFirst()
                    .orElseThrow(() -> ApiException.invalidField("riskAnswers." + key, "Unknown risk question '" + key + "'"));
            String v = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
            if (q.options().stream().noneMatch(o -> o.value().equals(v))) {
                throw ApiException.invalidField("riskAnswers." + key, "'" + q.text() + "' must be one of "
                        + q.options().stream().map(Option::value).toList());
            }
            clean.put(key, v);
        });
        return clean;
    }

    public static boolean complete(Map<String, String> answers) {
        return answers != null && QUESTIONS.stream().allMatch(q -> answers.containsKey(q.key()));
    }

    /** {@code failedRecently}: failed or rolled-back changes on the same services in the last 90 days. */
    public static Assessment assess(Change.Type type, Map<String, String> answers, Collection<BusinessService> services, Instant plannedStart,
                                    ZoneId zone, long failedRecently) {
        if (type == Change.Type.STANDARD) {
            return new Assessment(Change.Risk.LOW, 0, List.of(new Factor("standard", "Pre-approved standard change (template)", 0, "COMPUTED")));
        }
        List<Factor> factors = new ArrayList<>();
        for (Question q : QUESTIONS) {
            String answer = answers == null ? null : answers.get(q.key());
            Option o = answer == null ? q.options().get(1) : q.options().stream().filter(x -> x.value().equals(answer)).findFirst()
                    .orElse(q.options().get(1));
            if (o.points() > 0) {
                factors.add(new Factor(q.key(), q.text() + ": " + o.label() + (answer == null ? " (not answered yet)" : ""), o.points(), "ANSWER"));
            }
        }
        int tier = services.stream().mapToInt(BusinessService::getTier).min().orElse(3);
        if (tier == 1) {
            factors.add(new Factor("tier", "Affects a tier-1 (business-critical) service", 3, "COMPUTED"));
        } else if (tier == 2) {
            factors.add(new Factor("tier", "Affects a tier-2 service", 1, "COMPUTED"));
        }
        if (services.size() > 2) {
            factors.add(new Factor("breadth", "Affects " + services.size() + " services", 2, "COMPUTED"));
        }
        if (plannedStart != null) {
            ZonedDateTime local = plannedStart.atZone(zone);
            boolean weekday = local.getDayOfWeek() != DayOfWeek.SATURDAY && local.getDayOfWeek() != DayOfWeek.SUNDAY;
            if (weekday && local.getHour() >= 9 && local.getHour() < 18) {
                factors.add(new Factor("businessHours", "Planned during business hours", 1, "COMPUTED"));
            }
        }
        if (failedRecently > 0) {
            factors.add(new Factor("history", failedRecently + " failed or rolled-back change(s) on these services in the last 90 days",
                    (int) Math.min(4, failedRecently * 2), "COMPUTED"));
        }
        if (type == Change.Type.EMERGENCY) {
            factors.add(new Factor("emergency", "Emergency change (expedited, less time to test)", 2, "COMPUTED"));
        }
        int score = factors.stream().mapToInt(Factor::points).sum();
        return new Assessment(level(score), score, factors);
    }

    public static Change.Risk level(int score) {
        return score >= HIGH_FROM ? Change.Risk.HIGH : score >= MEDIUM_FROM ? Change.Risk.MEDIUM : Change.Risk.LOW;
    }
}

package com.nexus360.change;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.ai.AiService;
import com.nexus360.ai.LlmException;
import com.nexus360.ai.LlmProvider;
import com.nexus360.change.ChangeDtos.Conflict;
import com.nexus360.change.ChangeDtos.RiskAnalysis;
import com.nexus360.change.ChangeDtos.SimilarChange;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.security.Actor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SA AI change-risk analysis. The score and its factors are always the deterministic assessment ({@link ChangeRisk}); the
 * LLM (when configured) may raise or lower the overall level and adds concerns and recommendations grounded in the change,
 * its conflicts and the outcomes of earlier changes on the same services. Its output is validated (level LOW/MEDIUM/HIGH,
 * at least one recommendation); otherwise the deterministic engine answers and the result is labelled FALLBACK.
 */
@Component
@RequiredArgsConstructor
public class ChangeAiAdvisor {

    public static class ChangeRiskOut {
        @JsonPropertyDescription("Overall risk: LOW, MEDIUM or HIGH")
        public String riskLevel;
        @JsonPropertyDescription("Specific risks of this change grounded in the data, each a short sentence")
        public List<String> concerns;
        @JsonPropertyDescription("Concrete recommendations for the implementer and the CAB")
        public List<String> recommendations;
    }

    private final AiService ai;
    private final LlmProvider llm;
    private final ChangeService changes;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private record Evidence(Change change, List<Conflict> conflicts, List<SimilarChange> similar, List<String> services) {}

    public AiResult<RiskAnalysis> analyse(Long changeId, Actor actor) {
        Evidence e = tx.execute(s -> {
            Change c = changes.load(changeId);
            c.getRiskFactors().size();
            List<Long> serviceIds = c.getServices().stream().map(BusinessService::getId).toList();
            List<SimilarChange> similar = serviceIds.isEmpty() ? List.of() : jdbc.query("select distinct c.number, c.title, c.status, c.risk, c.closed_at "
                    + "from changes c join change_services cs on cs.change_id = c.id where cs.service_id in (:ids) and c.id <> :id "
                    + "and c.status in ('COMPLETED', 'FAILED', 'ROLLED_BACK') order by c.closed_at desc nulls last limit 5",
                    Map.of("ids", serviceIds, "id", c.getId()), (rs, n) -> new SimilarChange(rs.getString(1), rs.getString(2),
                            ChangeStatus.valueOf(rs.getString(3)), Change.Risk.valueOf(rs.getString(4)),
                            rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()));
            return new Evidence(c, changes.conflicts(c, c.getPlannedStart(), c.getPlannedEnd()), similar,
                    c.getServices().stream().map(x -> x.getName() + " (tier " + x.getTier() + ")").toList());
        });
        Change c = e.change();
        return ai.runGrounded("change_risk", c.getNumber() + " · " + c.getTitle(), actor, () -> viaLlm(e), () -> fallback(e));
    }

    private RiskAnalysis viaLlm(Evidence e) {
        Change c = e.change();
        String data = "<data>\nChange " + c.getNumber() + " (" + c.getType() + "): " + c.getTitle()
                + "\nDescription: " + nz(c.getDescription()) + "\nServices: " + String.join(", ", e.services())
                + "\nWindow: " + (c.getPlannedStart() == null ? "not planned" : c.getPlannedStart() + " to " + c.getPlannedEnd())
                + "\nImplementation plan: " + nz(c.getImplementationPlan()) + "\nRollback plan: " + nz(c.getRollbackPlan())
                + "\nTest plan: " + nz(c.getTestPlan()) + "\nQuestionnaire: " + c.getRiskAnswers()
                + "\nComputed risk: " + c.getRisk() + " (score " + c.getRiskScore() + "): "
                + c.getRiskFactors().stream().map(f -> f.label() + " +" + f.points()).collect(Collectors.joining("; "))
                + "\nConflicts: " + (e.conflicts().isEmpty() ? "none" : e.conflicts().stream().map(Conflict::number).collect(Collectors.joining(", ")))
                + "\nEarlier changes on these services: " + (e.similar().isEmpty() ? "none" : e.similar().stream()
                .map(s -> s.number() + " " + s.status()).collect(Collectors.joining(", "))) + "\n</data>";
        ChangeRiskOut out = llm.generate(AiService.systemPrompt("Assess the risk of an IT change for a Change Advisory Board."), data, ChangeRiskOut.class);
        String level = out == null || out.riskLevel == null ? "" : out.riskLevel.trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "MEDIUM", "HIGH").contains(level)) {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "riskLevel must be LOW, MEDIUM or HIGH");
        }
        List<String> recommendations = clean(out.recommendations);
        if (recommendations.isEmpty()) {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "No recommendations returned");
        }
        List<ChangeRisk.Factor> factors = new ArrayList<>(c.getRiskFactors());
        clean(out.concerns).forEach(x -> factors.add(new ChangeRisk.Factor("ai", x, 0, "AI")));
        return new RiskAnalysis(Change.Risk.valueOf(level), c.getRiskScore(), factors, recommendations, e.similar(),
                "LLM review of the change record; score and factors from the SA risk model");
    }

    /** Deterministic engine: computed level and rule-based recommendations from the questionnaire, schedule and history. */
    RiskAnalysis fallback(Evidence e) {
        Change c = e.change();
        Map<String, String> a = c.getRiskAnswers();
        List<String> rec = new ArrayList<>();
        if (!"YES".equals(a.get("tested"))) {
            rec.add("Test the change end to end in a non-production environment before the window");
        }
        if (!"YES".equals(a.get("rollbackTested"))) {
            rec.add("NOT_POSSIBLE".equals(a.get("rollbackTested")) ? "Rollback is not possible: agree a fix-forward plan and a go/no-go checkpoint with the CAB"
                    : "Rehearse the rollback and time it against the window");
        }
        if (c.getRiskFactors().stream().anyMatch(f -> f.key().equals("businessHours"))) {
            rec.add("Move the window outside business hours or announce the impact in advance");
        }
        if (c.getRiskFactors().stream().anyMatch(f -> f.key().equals("tier"))) {
            rec.add("Inform the service owner and publish a maintenance window on the status page");
        }
        if (!e.conflicts().isEmpty()) {
            rec.add("Resolve the scheduling conflict with " + e.conflicts().stream().map(Conflict::number).collect(Collectors.joining(", ")));
        }
        List<SimilarChange> failed = e.similar().stream().filter(s -> s.status() != ChangeStatus.COMPLETED).toList();
        if (!failed.isEmpty()) {
            rec.add("Review why " + failed.stream().map(SimilarChange::number).collect(Collectors.joining(", ")) + " failed before implementing");
        }
        if (c.getRisk() == Change.Risk.HIGH) {
            rec.add("High risk: the Change Advisory Board stage is required; have the implementer attend the CAB");
        }
        if (rec.isEmpty()) {
            rec.add("No specific concerns found: follow the implementation and test plan");
        }
        return new RiskAnalysis(c.getRisk(), c.getRiskScore(), c.getRiskFactors(), rec, e.similar(),
                "Deterministic SA risk model: questionnaire, service tier, schedule, conflicts and change history");
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).limit(8).toList();
    }
}

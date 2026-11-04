package com.nexus360.ai;

import com.nexus360.ai.AiDtos.MatchedIncident;
import com.nexus360.ai.AiDtos.ResolutionSuggestion;
import com.nexus360.ai.AiDtos.RunbookRef;
import com.nexus360.incident.DuplicateDetector;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.TextSimilarity;
import com.nexus360.knowledge.KnowledgeDtos.ArticleSummary;
import com.nexus360.knowledge.KnowledgeService;
import com.nexus360.runbook.Runbook;
import com.nexus360.runbook.RunbookRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Evidence for resolution advice and the deterministic suggestion built from it. Evidence is (1) recently resolved
 * incidents on the same service or category, scored with the duplicate detector's similarity, (2) knowledge articles
 * ranked by {@link KnowledgeService#recommend}, and (3) active runbooks scored by service, category and wording.
 *
 * <p>The confidence is a weighted blend of those signals (0–100):
 * {@code 55 × incident similarity × (0.5 + 0.5 × agreement on the cause) + 25 × article relevance + 20 × runbook fit},
 * plus at most 10 points from a keyword pattern when no resolved incident supports the cause. With no evidence it is 0.
 */
@Component
@RequiredArgsConstructor
public class ResolutionAdvisor {

    static final double MIN_INCIDENT_SIMILARITY = 0.25;
    static final double MIN_RUNBOOK_FIT = 0.3;
    private static final int POOL_SIZE = 300;
    private static final int HISTORY_DAYS = 180;

    public record SimilarIncident(Incident incident, double similarity) {}

    public record RunbookCandidate(Runbook runbook, double fit) {}

    public record Evidence(List<SimilarIncident> incidents, List<ArticleSummary> articles, List<RunbookCandidate> runbooks) {}

    private final IncidentRepository incidents;
    private final RunbookRepository runbooks;
    private final KnowledgeService knowledge;
    private final Clock clock;

    public Evidence gather(Incident i) {
        String text = i.getTitle() + " " + Objects.toString(i.getDescription(), "");
        Long serviceId = i.getService() == null ? null : i.getService().getId();
        Set<Long> family = new HashSet<>();
        family.add(i.getId());
        if (i.getParent() != null) {
            family.add(i.getParent().getId());
        }
        List<SimilarIncident> similar = incidents.findResolvedPool(i.getId(), serviceId == null ? -1L : serviceId, i.getCategory(),
                        clock.instant().minus(Duration.ofDays(HISTORY_DAYS)), PageRequest.of(0, POOL_SIZE)).stream()
                .filter(c -> !family.contains(c.getId()) && (c.getParent() == null || !family.contains(c.getParent().getId())))
                .filter(c -> c.getRootCause() != null || c.getResolutionNotes() != null)
                .map(c -> new SimilarIncident(c, DuplicateDetector.score(i.getTitle(), i.getDescription(), i.getService(), i.getCategory(), c)))
                .filter(s -> s.similarity() >= MIN_INCIDENT_SIMILARITY)
                .sorted(Comparator.comparingDouble(SimilarIncident::similarity).reversed())
                .limit(5)
                .toList();
        List<ArticleSummary> articles = knowledge.recommend(text, serviceId, 5);
        Set<String> terms = TextSimilarity.tokens(text);
        List<RunbookCandidate> fits = runbooks.findAllByOrderByTitleAsc().stream()
                .filter(Runbook::isActive)
                .map(r -> new RunbookCandidate(r, runbookFit(r, i, terms)))
                .filter(c -> c.fit() >= MIN_RUNBOOK_FIT)
                .sorted(Comparator.comparingDouble(RunbookCandidate::fit).reversed())
                .limit(3)
                .toList();
        return new Evidence(similar, articles, fits);
    }

    /** Deterministic suggestion; every number is derived from the evidence. */
    public ResolutionSuggestion suggest(Incident i, Evidence e) {
        String text = (i.getTitle() + " " + Objects.toString(i.getDescription(), "")).toLowerCase(Locale.ROOT);
        Map<String, Double> causeWeight = new LinkedHashMap<>();
        Map<String, Double> causeBest = new LinkedHashMap<>();
        double totalWeight = 0;
        for (SimilarIncident s : e.incidents()) {
            if (s.incident().getRootCause() == null || s.incident().getRootCause().isBlank()) {
                continue;
            }
            String cause = s.incident().getRootCause().trim();
            causeWeight.merge(cause, s.similarity(), Double::sum);
            causeBest.merge(cause, s.similarity(), Math::max);
            totalWeight += s.similarity();
        }
        String likelyCause;
        double incidentSignal = 0;
        double patternPoints = 0;
        List<String> basis = new ArrayList<>();
        if (!causeWeight.isEmpty()) {
            String best = causeWeight.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            double agreement = causeWeight.get(best) / totalWeight;
            incidentSignal = causeBest.get(best) * (0.5 + 0.5 * agreement);
            long supporting = e.incidents().stream().filter(s -> best.equals(Objects.toString(s.incident().getRootCause(), "").trim())).count();
            likelyCause = best;
            basis.add("root cause recorded on " + supporting + " similar resolved incident(s), best similarity "
                    + Math.round(causeBest.get(best) * 100) + "%");
        } else if (!e.incidents().isEmpty()) {
            SimilarIncident top = e.incidents().get(0);
            incidentSignal = top.similarity() * 0.5;
            likelyCause = "Same symptoms as " + top.incident().getNumber() + " (no root cause was recorded there)";
            basis.add("similar resolved incident " + top.incident().getNumber() + " (" + Math.round(top.similarity() * 100) + "%)");
        } else {
            FallbackEngine.CauseMatch pattern = FallbackEngine.matchCause(text);
            if (pattern != null) {
                likelyCause = pattern.cause() + " (inferred from the wording of the report)";
                patternPoints = Math.min(10, 5 * pattern.hits());
                basis.add("keyword pattern \"" + pattern.matched() + "\"");
            } else {
                likelyCause = "Not enough evidence to infer a likely cause";
            }
        }
        double articleSignal = e.articles().isEmpty() || e.articles().get(0).score() == null ? 0 : Math.min(1, e.articles().get(0).score());
        double runbookSignal = e.runbooks().isEmpty() ? 0 : e.runbooks().get(0).fit();
        if (articleSignal > 0) {
            basis.add("article " + e.articles().get(0).number() + " relevance " + Math.round(articleSignal * 100) + "%");
        }
        if (runbookSignal > 0) {
            basis.add("runbook '" + e.runbooks().get(0).runbook().getTitle() + "' fit " + Math.round(runbookSignal * 100) + "%");
        }
        int confidence = (int) Math.round(Math.min(100, 55 * incidentSignal + 25 * articleSignal + 20 * runbookSignal + patternPoints));

        RunbookRef runbook = e.runbooks().isEmpty() ? null : ref(e.runbooks().get(0).runbook());
        List<String> steps = new ArrayList<>();
        if (!e.runbooks().isEmpty()) {
            e.runbooks().get(0).runbook().getSteps().forEach(s -> steps.add(s.title() + (s.requiresApproval() ? " (needs approval)" : "")));
        }
        Set<String> notes = new LinkedHashSet<>();
        e.incidents().stream().map(s -> s.incident().getResolutionNotes()).filter(n -> n != null && !n.isBlank())
                .map(n -> n.startsWith("Resolved with parent") ? n.substring(n.indexOf(':') + 1).trim() : n.trim())
                .limit(3).forEach(notes::add);
        notes.forEach(n -> steps.add("Previously fixed by: " + n));
        e.articles().stream().limit(2).forEach(a -> steps.add("Follow " + a.number() + " — " + a.title()));
        if (steps.isEmpty()) {
            FallbackEngine.CauseMatch pattern = FallbackEngine.matchCause(text);
            if (pattern != null) {
                steps.addAll(pattern.checks());
            } else {
                steps.add("Collect error messages, screenshots and the time the problem started");
                steps.add("Check monitoring for the affected service");
            }
        }
        List<MatchedIncident> matched = e.incidents().stream().map(s -> new MatchedIncident(s.incident().getId(), s.incident().getNumber(),
                s.incident().getTitle(), s.incident().getRootCause(), Math.round(s.similarity() * 1000) / 1000.0)).toList();
        return new ResolutionSuggestion(likelyCause, confidence, steps.stream().limit(10).toList(), runbook,
                e.articles().stream().limit(3).toList(), matched,
                basis.isEmpty() ? "No similar resolved incident, article or runbook matched this incident." : "Based on " + String.join("; ", basis) + ".");
    }

    static RunbookRef ref(Runbook r) {
        return new RunbookRef(r.getId(), r.getTitle(), r.isRequiresApproval());
    }

    /** 0.5 for the same service, 0.3 for a matching category, up to 0.2 for shared wording. */
    static double runbookFit(Runbook r, Incident i, Set<String> incidentTerms) {
        double fit = 0;
        if (r.getService() != null && i.getService() != null && r.getService().getId().equals(i.getService().getId())) {
            fit += 0.5;
        }
        if (r.getCategories().contains(i.getCategory())) {
            fit += 0.3;
        }
        Set<String> runbookTerms = TextSimilarity.tokens(r.getTitle() + " " + Objects.toString(r.getDescription(), ""));
        if (!runbookTerms.isEmpty() && !incidentTerms.isEmpty()) {
            Set<String> shared = new HashSet<>(runbookTerms);
            shared.retainAll(incidentTerms);
            fit += 0.2 * Math.min(1, shared.size() / 3.0);
        }
        return Math.round(fit * 1000) / 1000.0;
    }
}

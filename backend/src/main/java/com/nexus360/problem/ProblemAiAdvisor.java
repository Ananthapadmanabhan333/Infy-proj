package com.nexus360.problem;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.ai.AiDtos.Hypothesis;
import com.nexus360.ai.AiService;
import com.nexus360.ai.LlmException;
import com.nexus360.ai.LlmProvider;
import com.nexus360.ai.LlmSchemas;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.TextSimilarity;
import com.nexus360.problem.ProblemDtos.RootCauseSuggestion;
import com.nexus360.security.Actor;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SA AI root-cause suggestions for a problem, grounded in its linked incidents (titles, recorded root causes, resolution
 * notes, timing). The LLM may only reason over the supplied records; its output is validated (1–4 hypotheses, likelihood
 * HIGH/MEDIUM/LOW). Without an LLM, or on any failure, the deterministic engine ranks the recorded root causes by frequency
 * and adds timing and symptom factors — and the result is labelled FALLBACK.
 */
@Component
@RequiredArgsConstructor
public class ProblemAiAdvisor {

    /** Structured output requested from the LLM. */
    public static class ProblemRootCauseOut {
        @JsonPropertyDescription("One to four root-cause hypotheses, most likely first")
        public List<LlmSchemas.Hypothesis> hypotheses;
        @JsonPropertyDescription("Factors the linked incidents have in common (time, service, symptom), each a short sentence")
        public List<String> commonFactors;
        @JsonPropertyDescription("A workaround supported by the data, or an empty string")
        public String suggestedWorkaround;
    }

    private final AiService ai;
    private final LlmProvider llm;
    private final ProblemService problems;
    private final ProblemIncidentRepository links;
    private final IncidentRepository incidents;
    private final TransactionTemplate tx;

    private record Evidence(String number, String title, String description, String service, int incidents, List<Incident> linked) {}

    public AiResult<RootCauseSuggestion> rootCause(Long problemId, Actor actor) {
        Evidence e = tx.execute(s -> {
            Problem p = problems.load(problemId);
            List<Incident> linked = incidents.findAllById(links.findIncidentIds(problemId)).stream()
                    .sorted(Comparator.comparing(Incident::getCreatedAt)).toList();
            linked.forEach(i -> {
                if (i.getService() != null) {
                    i.getService().getName();
                }
            });
            String service = p.getServices().stream().map(x -> x.getName()).collect(Collectors.joining(", "));
            return new Evidence(p.getNumber(), p.getTitle(), p.getDescription(), service, linked.size(), linked);
        });
        return ai.runGrounded("problem_root_cause", e.number() + " · " + e.title(), actor, () -> viaLlm(e), () -> fallback(e));
    }

    private RootCauseSuggestion viaLlm(Evidence e) {
        String records = e.linked().stream().limit(40).map(i -> i.getNumber() + " [" + i.getStatus() + ", " + i.getCreatedAt() + "] " + i.getTitle()
                + (i.getRootCause() == null ? "" : " | root cause: " + i.getRootCause())
                + (i.getResolutionNotes() == null ? "" : " | resolution: " + i.getResolutionNotes())).collect(Collectors.joining("\n"));
        ProblemRootCauseOut out = llm.generate(AiService.systemPrompt("Suggest probable root causes for an IT problem record from its linked incidents."),
                "<data>\nProblem " + e.number() + ": " + e.title() + "\nDescription: " + (e.description() == null ? "-" : e.description())
                        + "\nServices: " + (e.service().isBlank() ? "-" : e.service()) + "\nLinked incidents (" + e.incidents() + "):\n"
                        + (records.isBlank() ? "none" : records) + "\n</data>", ProblemRootCauseOut.class);
        List<Hypothesis> hypotheses = new ArrayList<>();
        for (LlmSchemas.Hypothesis h : out == null || out.hypotheses == null ? List.<LlmSchemas.Hypothesis>of() : out.hypotheses) {
            if (h == null || h.cause == null || h.cause.isBlank()) {
                continue;
            }
            String likelihood = h.likelihood == null ? "" : h.likelihood.trim().toUpperCase(Locale.ROOT);
            hypotheses.add(new Hypothesis(h.cause.trim(), List.of("HIGH", "MEDIUM", "LOW").contains(likelihood) ? likelihood : "MEDIUM",
                    clean(h.evidence), clean(h.suggestedChecks)));
        }
        if (hypotheses.isEmpty()) {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "No usable hypotheses returned");
        }
        return new RootCauseSuggestion(hypotheses.stream().limit(4).toList(), clean(out.commonFactors),
                out.suggestedWorkaround == null || out.suggestedWorkaround.isBlank() ? null : out.suggestedWorkaround.trim(), e.incidents(),
                "LLM analysis of " + e.incidents() + " linked incident(s)");
    }

    /** Deterministic engine: recorded root causes by frequency, plus timing and symptom factors computed from the incidents. */
    RootCauseSuggestion fallback(Evidence e) {
        List<Incident> linked = e.linked();
        Map<String, List<Incident>> causes = new LinkedHashMap<>();
        for (Incident i : linked) {
            if (i.getRootCause() != null && !i.getRootCause().isBlank()) {
                causes.computeIfAbsent(i.getRootCause().trim(), k -> new ArrayList<>()).add(i);
            }
        }
        int withCause = causes.values().stream().mapToInt(List::size).sum();
        List<Hypothesis> hypotheses = causes.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<Incident>> x) -> x.getValue().size()).reversed())
                .limit(4)
                .map(x -> {
                    double share = (double) x.getValue().size() / Math.max(1, withCause);
                    String likelihood = share >= 0.5 ? "HIGH" : share >= 0.25 ? "MEDIUM" : "LOW";
                    List<String> checks = x.getValue().stream().map(Incident::getResolutionNotes).filter(n -> n != null && !n.isBlank())
                            .distinct().limit(3).map(n -> "Previously resolved by: " + n).toList();
                    return new Hypothesis(x.getKey(), likelihood, List.of("Recorded as the root cause of " + x.getValue().size() + " of "
                            + withCause + " linked incident(s): " + x.getValue().stream().map(Incident::getNumber).limit(6).collect(Collectors.joining(", "))),
                            checks.isEmpty() ? List.of("Review the change and deployment history of the affected service around the first incident")
                                    : checks);
                }).collect(Collectors.toCollection(ArrayList::new));
        List<String> factors = new ArrayList<>();
        if (!linked.isEmpty()) {
            Map<Integer, Long> byHour = linked.stream().collect(Collectors.groupingBy(i -> i.getCreatedAt().atZone(ZoneId.of("Asia/Kolkata")).getHour(),
                    Collectors.counting()));
            byHour.entrySet().stream().max(Map.Entry.comparingByValue()).filter(x -> x.getValue() >= Math.max(2, linked.size() / 2))
                    .ifPresent(x -> factors.add(x.getValue() + " of " + linked.size() + " incidents started between " + x.getKey() + ":00 and "
                            + (x.getKey() + 1) + ":00 (IST)"));
            Map<String, Integer> tokens = new HashMap<>();
            linked.forEach(i -> TextSimilarity.tokens(i.getTitle() + " " + (i.getDescription() == null ? "" : i.getDescription()))
                    .forEach(t -> tokens.merge(t, 1, Integer::sum)));
            List<String> common = tokens.entrySet().stream().filter(x -> x.getValue() >= Math.max(2, (linked.size() + 1) / 2))
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5).map(Map.Entry::getKey).toList();
            if (!common.isEmpty()) {
                factors.add("Common symptom terms: " + String.join(", ", common));
            }
            long services = linked.stream().map(i -> i.getService() == null ? "-" : i.getService().getName()).distinct().count();
            factors.add(services == 1 ? "All linked incidents affect the same service" : "Linked incidents span " + services + " services");
        }
        if (hypotheses.isEmpty()) {
            hypotheses.add(new Hypothesis("Not enough recorded root causes to rank; investigate the common factors below", "LOW",
                    factors.isEmpty() ? List.of("No linked incidents") : factors,
                    List.of("Link the related incidents to this problem", "Record root causes when resolving incidents")));
        }
        String workaround = linked.stream().filter(i -> i.getResolutionNotes() != null && !i.getResolutionNotes().isBlank())
                .collect(Collectors.groupingBy(i -> i.getResolutionNotes().trim(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return new RootCauseSuggestion(hypotheses, factors, workaround, linked.size(),
                "Deterministic analysis of " + linked.size() + " linked incident(s): recorded root causes ranked by frequency");
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).limit(8).toList();
    }
}

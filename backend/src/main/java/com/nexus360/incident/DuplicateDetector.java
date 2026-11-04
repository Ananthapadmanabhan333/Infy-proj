package com.nexus360.incident;

import com.nexus360.admin.SettingsService;
import com.nexus360.cmdb.BusinessService;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Scores unresolved recent incidents against a new report.
 *
 * <pre>
 *   score = 0.6 × text similarity
 *         + 0.20 same business service      (× 0.5 instead when both services are known and differ)
 *         + 0.05 same category
 *         + 0.20 same service and the same failure symptom (outage, slowness, sign-in, data)
 * </pre>
 *
 * The symptom signal is what separates paraphrased reports of one outage ("portal not loading, error 503" /
 * "cannot access payroll") from unrelated issues on the same service ("payroll shows wrong tax deduction").
 */
@Component
@RequiredArgsConstructor
public class DuplicateDetector {

    public record Match(Incident incident, double score) {}

    public enum Symptom { OUTAGE, SLOW, SIGN_IN, DATA }

    private static final Map<Symptom, List<String>> SYMPTOM_TERMS = Map.of(
            Symptom.OUTAGE, List.of("down", "outage", "unavailable", "not available", "503", "502", "500 error", "not loading", "won't load",
                    "doesn't load", "does not load", "cannot access", "can't access", "cant access", "unable to access", "unable to open",
                    "cannot open", "can't open", "inaccessible", "not accessible", "not working", "spinning", "blank page", "timeout",
                    "timed out", "fails", "failing", "error page", "crash", "not responding", "unreachable"),
            Symptom.SLOW, List.of("slow", "latency", "lag", "sluggish", "takes long", "taking long", "delay", "performance"),
            Symptom.SIGN_IN, List.of("login", "log in", "sign in", "signin", "password", "sso", "mfa", "locked out", "access denied",
                    "unauthorized", "unauthorised", "credentials"),
            Symptom.DATA, List.of("wrong", "incorrect", "missing", "mismatch", "not updated", "calculation", "miscalculated", "discrepancy"));

    private final IncidentRepository incidents;
    private final SettingsService settings;
    private final Clock clock;

    public List<Match> candidates(String title, String description, BusinessService service, Category category, Long excludeId,
                                  double minimumScore, int limit) {
        int windowHours = settings.getInt(SettingsService.DUPLICATE_WINDOW_HOURS, 72);
        List<Incident> pool = incidents.findDuplicateCandidates(IncidentStatus.ACTIVE,
                clock.instant().minus(Duration.ofHours(windowHours)), excludeId == null ? -1L : excludeId);
        return pool.stream()
                .map(candidate -> new Match(candidate, score(title, description, service, category, candidate)))
                .filter(m -> m.score() >= minimumScore)
                .sorted(Comparator.comparingDouble(Match::score).reversed())
                .limit(limit)
                .toList();
    }

    public static double score(String title, String description, BusinessService service, Category category, Incident candidate) {
        double text = TextSimilarity.score(title, description, candidate.getTitle(), candidate.getDescription());
        Long serviceId = service == null ? null : service.getId();
        Long candidateServiceId = candidate.getService() == null ? null : candidate.getService().getId();
        boolean sameService = serviceId != null && Objects.equals(serviceId, candidateServiceId);
        double score = text * 0.6;
        if (sameService) {
            score += 0.2;
        } else if (serviceId != null && candidateServiceId != null) {
            score *= 0.5;
        }
        if (category != null && category == candidate.getCategory()) {
            score += 0.05;
        }
        if (sameService) {
            Set<Symptom> mine = symptoms(title + " " + Objects.toString(description, ""));
            mine.retainAll(symptoms(candidate.getTitle() + " " + Objects.toString(candidate.getDescription(), "")));
            if (!mine.isEmpty()) {
                score += 0.2;
            }
        }
        return Math.round(Math.min(1.0, score) * 1000) / 1000.0;
    }

    public static Set<Symptom> symptoms(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Set<Symptom> found = EnumSet.noneOf(Symptom.class);
        SYMPTOM_TERMS.forEach((symptom, terms) -> {
            if (terms.stream().anyMatch(t::contains)) {
                found.add(symptom);
            }
        });
        return found;
    }

    public double autoLinkThreshold() {
        return settings.getDouble(SettingsService.DUPLICATE_THRESHOLD, 0.48);
    }

    public double suggestionThreshold() {
        return settings.getDouble(SettingsService.DUPLICATE_SUGGESTION_THRESHOLD, 0.30);
    }
}

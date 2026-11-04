package com.nexus360.incident;

import com.nexus360.cmdb.BusinessService;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Rule-based classifier used on every new incident (and as the AI fallback). It detects the affected
 * business service from service names/keywords and the category from an IT keyword taxonomy.
 */
@Component
public class IncidentClassifier {

    public record Result(Category category, BusinessService service, double confidence, String rationale) {}

    /** Administrator configuration: extra keywords per category, and categories that must not be chosen. */
    public record Rules(Map<Category, List<String>> extraKeywords, Set<Category> inactive) {
        public static final Rules NONE = new Rules(Map.of(), Set.of());

        public Rules {
            extraKeywords = extraKeywords == null ? Map.of() : extraKeywords;
            inactive = inactive == null ? Set.of() : inactive;
        }

        boolean usable(Category category) {
            return category == Category.OTHER || !inactive.contains(category);
        }
    }

    private static final Map<Category, List<String>> CATEGORY_TERMS = new EnumMap<>(Map.of(
            Category.NETWORK, List.of("vpn", "network", "wifi", "wi-fi", "internet", "latency", "dns", "firewall", "connectivity",
                    "packet loss", "router", "switch", "bandwidth", "lan", "wan", "proxy", "ping"),
            Category.EMAIL, List.of("email", "e-mail", "outlook", "mailbox", "smtp", "exchange", "mail", "calendar invite", "inbox"),
            Category.ACCESS, List.of("password", "login", "log in", "sign in", "signin", "access denied", "permission", "locked out",
                    "account locked", "mfa", "2fa", "sso", "unauthori", "credentials", "reset my"),
            Category.DATABASE, List.of("database", " db ", "sql", "query", "deadlock", "connection pool", "postgres", "oracle",
                    "replication", "tablespace"),
            Category.HARDWARE, List.of("laptop", "printer", "monitor", "keyboard", "mouse", "desktop", "battery", "docking",
                    "hardware", "screen", "headset", "scanner", "charger", "toner"),
            Category.SECURITY, List.of("phishing", "malware", "virus", "ransomware", "suspicious", "breach", "security", "spam",
                    "compromised", "hacked"),
            Category.FACILITIES, List.of("air conditioning", "aircon", " ac ", "desk", "meeting room", "building", "badge", "parking",
                    "lights", "facilities", "elevator", "lift", "pantry", "cafeteria"),
            Category.APPLICATION, List.of("application", "app", "portal", "website", "page", "error", "503", "500", "crash", "bug",
                    "not loading", "timeout", "payroll", "hr portal", "erp", "crm", "report", "screen freezes")));

    public Result classify(String title, String description, List<BusinessService> services) {
        return classify(title, description, services, Rules.NONE);
    }

    public Result classify(String title, String description, List<BusinessService> services, Rules rules) {
        Rules config = rules == null ? Rules.NONE : rules;
        String text = " " + ((title == null ? "" : title) + " " + (description == null ? "" : description)).toLowerCase(Locale.ROOT) + " ";

        BusinessService bestService = null;
        int bestServiceScore = 0;
        for (BusinessService service : services) {
            int score = mentions(text, service.getName()) ? 3 : 0;
            for (String keyword : service.keywordList()) {
                if (mentions(text, keyword)) {
                    score += 2;
                }
            }
            if (score > bestServiceScore) {
                bestServiceScore = score;
                bestService = service;
            }
        }

        Category bestCategory = Category.OTHER;
        int bestCategoryHits = 0;
        for (Category category : Category.values()) {
            if (!config.usable(category)) {
                continue;
            }
            List<String> terms = new java.util.ArrayList<>(CATEGORY_TERMS.getOrDefault(category, List.of()));
            terms.addAll(config.extraKeywords().getOrDefault(category, List.of()));
            int hits = (int) terms.stream().map(String::trim).distinct().filter(term -> mentions(text, term)).count();
            if (hits == 0) {
                continue;
            }
            // Application terms are generic; prefer a more specific category on ties.
            boolean better = hits > bestCategoryHits || (hits == bestCategoryHits && hits > 0 && bestCategory == Category.APPLICATION);
            if (better) {
                bestCategoryHits = hits;
                bestCategory = category;
            }
        }

        String rationale;
        if (bestService != null && bestService.getDefaultCategory() != null && config.usable(bestService.getDefaultCategory())
                && (bestCategoryHits <= 1 || bestCategory == Category.APPLICATION || bestCategory == Category.OTHER)) {
            bestCategory = bestService.getDefaultCategory();
            rationale = "Matched service '" + bestService.getName() + "' and used its default category";
        } else if (bestCategoryHits > 0) {
            rationale = "Matched " + bestCategoryHits + " " + bestCategory.name().toLowerCase(Locale.ROOT) + " term(s)"
                    + (bestService != null ? " and service '" + bestService.getName() + "'" : "");
        } else {
            rationale = bestService != null ? "Matched service '" + bestService.getName() + "'" : "No rule matched; classified as OTHER";
        }
        double confidence = Math.min(0.95, 0.35 + 0.12 * bestCategoryHits + (bestService != null ? 0.08 * Math.min(bestServiceScore, 5) : 0));
        if (bestCategoryHits == 0 && bestService == null) {
            confidence = 0.2;
        }
        return new Result(bestCategory, bestService, Math.round(confidence * 100) / 100.0, rationale);
    }

    private static final Map<String, Pattern> TERM_PATTERNS = new ConcurrentHashMap<>();

    /**
     * Whether {@code text} mentions {@code term} as a word: the term must start at a word boundary, and short terms
     * (under 5 characters) must also end at one (an optional plural "s" is allowed), so "db" does not match inside
     * "feedback" or a ticket reference, "wan" inside "want" or "503" inside "1503". Longer terms may carry endings
     * ("printers", "unauthorised").
     */
    static boolean mentions(String text, String term) {
        String t = term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return false;
        }
        Pattern pattern = TERM_PATTERNS.computeIfAbsent(t, k ->
                Pattern.compile("(?<![a-z0-9])" + Pattern.quote(k) + (k.length() < 5 ? "s?(?![a-z0-9])" : "")));
        return pattern.matcher(text).find();
    }
}

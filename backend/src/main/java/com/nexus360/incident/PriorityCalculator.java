package com.nexus360.incident;

import java.util.List;
import java.util.Locale;

/**
 * ITIL impact × urgency priority matrix.
 *
 * <pre>
 *                 Urgency HIGH   MEDIUM   LOW
 *   Impact HIGH   CRITICAL       HIGH     MEDIUM
 *   Impact MEDIUM HIGH           MEDIUM   LOW
 *   Impact LOW    MEDIUM         LOW      LOW
 * </pre>
 */
public final class PriorityCalculator {

    private static final Priority[][] MATRIX = {
            {Priority.CRITICAL, Priority.HIGH, Priority.MEDIUM},
            {Priority.HIGH, Priority.MEDIUM, Priority.LOW},
            {Priority.MEDIUM, Priority.LOW, Priority.LOW},
    };

    private static final List<String> HIGH_URGENCY = List.of("outage", "down", "not accessible", "inaccessible", "cannot access",
            "can't access", "cant access", "unable to access", "unable to log", "not loading", "not working", "unavailable", "all users",
            "everyone", "entire", "production", "urgent", "critical", "deadline", "data loss", "breach", "ransomware", "503", "500 error");
    private static final List<String> LOW_URGENCY = List.of("question", "how to", "how do i", "request", "when possible",
            "cosmetic", "minor", "typo", "suggestion", "nice to have");

    private PriorityCalculator() {}

    public static Priority calculate(Level impact, Level urgency) {
        return MATRIX[impact.ordinal()][urgency.ordinal()];
    }

    /**
     * Impact from the number of affected users and the number of linked reports, raised one level for
     * business-critical (tier 1) services.
     */
    public static Level deriveImpact(int affectedUsers, Integer serviceTier, int reports) {
        Level byUsers = affectedUsers >= 50 ? Level.HIGH : affectedUsers >= 5 ? Level.MEDIUM : Level.LOW;
        Level byReports = reports >= 5 ? Level.HIGH : reports >= 3 ? Level.MEDIUM : Level.LOW;
        Level impact = higher(byUsers, byReports);
        if (serviceTier != null && serviceTier == 1) {
            impact = raise(impact);
        }
        return impact;
    }

    public static Level deriveUrgency(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (HIGH_URGENCY.stream().anyMatch(t::contains)) {
            return Level.HIGH;
        }
        if (LOW_URGENCY.stream().anyMatch(t::contains)) {
            return Level.LOW;
        }
        return Level.MEDIUM;
    }

    public static Level higher(Level a, Level b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    public static Level raise(Level level) {
        return level == Level.LOW ? Level.MEDIUM : Level.HIGH;
    }
}

package com.nexus360.alert;

import com.nexus360.incident.Level;
import java.util.Locale;

/** Alert severity, most severe first. {@link #parse} maps the vocabulary of monitoring tools (critical, error, warn, page, ...). */
public enum AlertSeverity {
    CRITICAL(5), MAJOR(4), MINOR(3), WARNING(2), INFO(1);

    private final int rank;

    AlertSeverity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean atLeast(AlertSeverity other) {
        return rank >= other.rank;
    }

    /** {@code null} when the text is empty or unknown. */
    public static AlertSeverity parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "critical", "crit", "fatal", "emergency", "page", "p1", "sev1", "disaster" -> CRITICAL;
            case "major", "error", "err", "high", "p2", "sev2", "alert" -> MAJOR;
            case "minor", "medium", "average", "p3", "sev3" -> MINOR;
            case "warning", "warn", "low", "p4", "sev4" -> WARNING;
            case "info", "information", "informational", "none", "ok", "debug", "notice", "p5", "sev5" -> INFO;
            default -> null;
        };
    }

    /** Incident impact for an alert converted to an incident (critical/major → HIGH, minor/warning → MEDIUM, info → LOW). */
    public Level impact() {
        return switch (this) {
            case CRITICAL, MAJOR -> Level.HIGH;
            case MINOR, WARNING -> Level.MEDIUM;
            case INFO -> Level.LOW;
        };
    }

    /** Incident urgency (critical → HIGH, major/minor/warning → MEDIUM, info → LOW). */
    public Level urgency() {
        return switch (this) {
            case CRITICAL -> Level.HIGH;
            case MAJOR, MINOR, WARNING -> Level.MEDIUM;
            case INFO -> Level.LOW;
        };
    }
}

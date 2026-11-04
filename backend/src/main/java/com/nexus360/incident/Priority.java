package com.nexus360.incident;

/** ITIL priority, P1 (critical) to P4 (low). Declaration order is significant: lower ordinal = more urgent. */
public enum Priority {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW;

    public String label() {
        return "P" + (ordinal() + 1);
    }

    public boolean isHigherThan(Priority other) {
        return ordinal() < other.ordinal();
    }
}

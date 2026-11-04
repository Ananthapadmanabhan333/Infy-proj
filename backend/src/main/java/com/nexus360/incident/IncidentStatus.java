package com.nexus360.incident;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Release 2 lifecycle. Cancelling is {@link #CLOSED} with resolution code {@link ResolutionCode#CANCELLED};
 * Release 1 values were migrated (OPEN→NEW, ON_HOLD→PENDING, CANCELLED→CLOSED + CANCELLED).
 */
public enum IncidentStatus {
    NEW,
    ASSIGNED,
    IN_PROGRESS,
    PENDING,
    RESOLVED,
    CLOSED,
    REOPENED;

    /** Statuses in which the incident still needs work. */
    public static final Set<IncidentStatus> ACTIVE = Collections.unmodifiableSet(EnumSet.of(NEW, ASSIGNED, IN_PROGRESS, PENDING, REOPENED));

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }

    /** SQL list of the active statuses, for native queries. */
    public static String activeSqlList() {
        return "('NEW','ASSIGNED','IN_PROGRESS','PENDING','REOPENED')";
    }
}

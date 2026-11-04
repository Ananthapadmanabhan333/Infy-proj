package com.nexus360.problem;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * NEW, INVESTIGATING, ROOT_CAUSE_IDENTIFIED, KNOWN_ERROR, RESOLVED, CLOSED. A root cause can be disproved (back to
 * INVESTIGATING), a fix can follow the root cause directly (ROOT_CAUSE_IDENTIFIED to RESOLVED), a resolved problem can
 * recur (to INVESTIGATING) and NEW or INVESTIGATING problems can be closed as cancelled (with a reason).
 */
public enum ProblemStatus {
    NEW, INVESTIGATING, ROOT_CAUSE_IDENTIFIED, KNOWN_ERROR, RESOLVED, CLOSED;

    public static final Set<ProblemStatus> OPEN = EnumSet.of(NEW, INVESTIGATING, ROOT_CAUSE_IDENTIFIED, KNOWN_ERROR);

    private static final Map<ProblemStatus, Set<ProblemStatus>> ALLOWED = Map.of(
            NEW, EnumSet.of(INVESTIGATING, CLOSED),
            INVESTIGATING, EnumSet.of(ROOT_CAUSE_IDENTIFIED, CLOSED),
            ROOT_CAUSE_IDENTIFIED, EnumSet.of(KNOWN_ERROR, RESOLVED, INVESTIGATING),
            KNOWN_ERROR, EnumSet.of(RESOLVED, INVESTIGATING),
            RESOLVED, EnumSet.of(CLOSED, INVESTIGATING),
            CLOSED, EnumSet.noneOf(ProblemStatus.class));

    public Set<ProblemStatus> next() {
        return ALLOWED.get(this);
    }

    public boolean canMoveTo(ProblemStatus to) {
        return ALLOWED.get(this).contains(to);
    }
}

package com.nexus360.change;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Change lifecycle, enforced server-side: DRAFT, SUBMITTED, ASSESSMENT, AWAITING_APPROVAL, APPROVED, SCHEDULED, IMPLEMENTING,
 * then COMPLETED, FAILED or ROLLED_BACK; REJECTED and CANCELLED end a change early. APPROVED and REJECTED are set only by
 * the approval engine (CAB); STANDARD changes are pre-approved on submission, EMERGENCY changes go straight to the
 * emergency CAB. A rejected change can be reworked (back to DRAFT), a scheduled one unscheduled (back to APPROVED) and a
 * failed one rolled back.
 */
public enum ChangeStatus {
    DRAFT, SUBMITTED, ASSESSMENT, AWAITING_APPROVAL, APPROVED, SCHEDULED, IMPLEMENTING, COMPLETED, FAILED, ROLLED_BACK, REJECTED, CANCELLED;

    /** Changes whose planned window counts for conflicts, the calendar and maintenance. */
    public static final Set<ChangeStatus> PLANNED = EnumSet.of(AWAITING_APPROVAL, APPROVED, SCHEDULED, IMPLEMENTING);
    public static final Set<ChangeStatus> CLOSED = EnumSet.of(COMPLETED, FAILED, ROLLED_BACK, REJECTED, CANCELLED);
    public static final Set<ChangeStatus> OUTCOMES = EnumSet.of(COMPLETED, FAILED, ROLLED_BACK);
    public static final Set<ChangeStatus> EDITABLE = EnumSet.of(DRAFT, SUBMITTED, ASSESSMENT, REJECTED);
    /** Transitions only the approval engine performs. */
    public static final Set<ChangeStatus> ENGINE_ONLY = EnumSet.of(APPROVED, REJECTED);

    private static final Map<ChangeStatus, Set<ChangeStatus>> ALLOWED = Map.ofEntries(
            Map.entry(DRAFT, EnumSet.of(SUBMITTED, CANCELLED)),
            Map.entry(SUBMITTED, EnumSet.of(ASSESSMENT, CANCELLED)),
            Map.entry(ASSESSMENT, EnumSet.of(AWAITING_APPROVAL, DRAFT, CANCELLED)),
            Map.entry(AWAITING_APPROVAL, EnumSet.of(APPROVED, REJECTED, CANCELLED)),
            Map.entry(APPROVED, EnumSet.of(SCHEDULED, CANCELLED)),
            Map.entry(SCHEDULED, EnumSet.of(IMPLEMENTING, APPROVED, CANCELLED)),
            Map.entry(IMPLEMENTING, EnumSet.of(COMPLETED, FAILED, ROLLED_BACK)),
            Map.entry(FAILED, EnumSet.of(ROLLED_BACK)),
            Map.entry(REJECTED, EnumSet.of(DRAFT)),
            Map.entry(COMPLETED, EnumSet.noneOf(ChangeStatus.class)),
            Map.entry(ROLLED_BACK, EnumSet.noneOf(ChangeStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(ChangeStatus.class)));

    public Set<ChangeStatus> next() {
        return ALLOWED.get(this);
    }

    public boolean canMoveTo(ChangeStatus to) {
        return ALLOWED.get(this).contains(to);
    }
}

package com.nexus360.alert;

import java.time.Instant;
import java.util.Collection;

/**
 * Extension point for escalating unacknowledged alerts (docs/RELEASE3_SPEC.md §5: "escalation hook for on-call, BE-4 completes it").
 * Every Spring bean implementing this interface is a candidate; for each alert the policy with the lowest {@link #order()} whose
 * {@link #appliesTo} returns true is used. The built-in {@link TeamLeadEscalationPolicy} has order 1000, so an on-call policy with
 * a lower order (e.g. 100) takes over for the alerts it accepts and the built-in one stays the fallback.
 *
 * <p>Contract: {@link AlertEscalationService} asks {@link #nextEscalationAt} when an alert opens (reference = the time it became
 * active) and after every escalation (reference = the escalation time); it stores the answer in {@code alerts.next_escalation_at}.
 * Its scheduler (advisory lock 360008, every {@code app.alerts.escalation-poll-interval-ms}) claims due OPEN alerts with a
 * conditional update on {@code escalation_level}, so every level is escalated exactly once even with several instances, then calls
 * {@link #escalate} in the same transaction (an exception rolls the claim back and the level is retried on the next run).
 * Acknowledging, resolving or suppressing an alert clears the schedule.</p>
 */
public interface AlertEscalationPolicy {

    /** Short identifier recorded in the alert history, e.g. {@code team-lead} or {@code on-call}. */
    String name();

    /** Lower is consulted first. The built-in policy uses 1000. */
    default int order() {
        return 100;
    }

    /** Whether this policy handles the alert (e.g. its team has an on-call schedule). */
    boolean appliesTo(Alert alert);

    /**
     * When the alert must be escalated to level {@code alert.getEscalationLevel() + 1}, or {@code null} for no further escalation.
     *
     * @param reference the time the alert became active, or the time of its last escalation
     */
    Instant nextEscalationAt(Alert alert, Instant reference);

    /**
     * Escalates the alert to {@code level} (notify or page people). Runs inside the scheduler's transaction; the caller updates the
     * alert's escalation fields and history afterwards.
     */
    Outcome escalate(Alert alert, int level, Instant now);

    /** Who was notified and a short note for the alert history (e.g. "Escalated to the team lead Priya Raman"). */
    record Outcome(Collection<Long> notifiedUserIds, String note) {}
}

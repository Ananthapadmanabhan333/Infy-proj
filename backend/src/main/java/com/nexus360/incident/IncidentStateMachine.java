package com.nexus360.incident;

import static com.nexus360.incident.IncidentStatus.ASSIGNED;
import static com.nexus360.incident.IncidentStatus.CLOSED;
import static com.nexus360.incident.IncidentStatus.IN_PROGRESS;
import static com.nexus360.incident.IncidentStatus.NEW;
import static com.nexus360.incident.IncidentStatus.PENDING;
import static com.nexus360.incident.IncidentStatus.REOPENED;
import static com.nexus360.incident.IncidentStatus.RESOLVED;

import com.nexus360.security.Actor;
import com.nexus360.user.Role;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allowed status transitions, and which of them an employee may perform on their own incident.
 *
 * <pre>
 *   NEW         → ASSIGNED, IN_PROGRESS, PENDING, RESOLVED, CLOSED (cancel)
 *   ASSIGNED    → IN_PROGRESS, PENDING, RESOLVED, CLOSED (cancel)
 *   IN_PROGRESS → PENDING, RESOLVED
 *   PENDING     → IN_PROGRESS, RESOLVED
 *   RESOLVED    → CLOSED, REOPENED
 *   REOPENED    → ASSIGNED, IN_PROGRESS, PENDING, RESOLVED
 *   CLOSED      → —
 * </pre>
 */
public final class IncidentStateMachine {

    private static final Map<IncidentStatus, Set<IncidentStatus>> TRANSITIONS = new EnumMap<>(IncidentStatus.class);

    static {
        TRANSITIONS.put(NEW, EnumSet.of(ASSIGNED, IN_PROGRESS, PENDING, RESOLVED, CLOSED));
        TRANSITIONS.put(ASSIGNED, EnumSet.of(IN_PROGRESS, PENDING, RESOLVED, CLOSED));
        TRANSITIONS.put(IN_PROGRESS, EnumSet.of(PENDING, RESOLVED));
        TRANSITIONS.put(PENDING, EnumSet.of(IN_PROGRESS, RESOLVED));
        TRANSITIONS.put(RESOLVED, EnumSet.of(CLOSED, REOPENED));
        TRANSITIONS.put(REOPENED, EnumSet.of(ASSIGNED, IN_PROGRESS, PENDING, RESOLVED));
        TRANSITIONS.put(CLOSED, EnumSet.noneOf(IncidentStatus.class));
    }

    private IncidentStateMachine() {}

    public static boolean isAllowed(IncidentStatus from, IncidentStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** CLOSED reached from NEW or ASSIGNED is a cancellation (no work was done), recorded as resolution code CANCELLED. */
    public static boolean isCancellation(IncidentStatus from, IncidentStatus to) {
        return to == CLOSED && (from == NEW || from == ASSIGNED);
    }

    /** Transitions the actor may trigger on this incident, in workflow order. */
    public static List<IncidentStatus> allowedFor(Incident incident, Actor actor) {
        IncidentStatus from = incident.getStatus();
        Set<IncidentStatus> targets = EnumSet.noneOf(IncidentStatus.class);
        targets.addAll(TRANSITIONS.getOrDefault(from, Set.of()));
        if (actor.role() == Role.AUDITOR) {
            return List.of();
        }
        if (!actor.isSystem() && !actor.isStaff()) {
            if (!IncidentAccess.isRequesterOrCreator(incident, actor)) {
                return List.of();
            }
            // Employees can withdraw an untouched report, confirm a resolution, or reopen it.
            Set<IncidentStatus> employee = EnumSet.noneOf(IncidentStatus.class);
            if (from == NEW || from == ASSIGNED) {
                employee.add(CLOSED);
            }
            if (from == RESOLVED) {
                employee.add(CLOSED);
                employee.add(REOPENED);
            }
            targets.retainAll(employee);
        }
        if (incident.getAssignee() == null) {
            targets.remove(ASSIGNED);
        }
        return targets.stream().sorted().toList();
    }
}

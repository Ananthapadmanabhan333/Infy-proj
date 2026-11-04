package com.nexus360.incident;

import com.nexus360.common.ApiException;
import com.nexus360.security.Actor;
import com.nexus360.user.Role;

/** Row-level visibility: staff and auditors see every incident, employees only those they reported. */
public final class IncidentAccess {

    private IncidentAccess() {}

    public static boolean canView(Incident incident, Actor actor) {
        if (actor.isSystem() || actor.isStaff() || actor.role() == Role.AUDITOR) {
            return true;
        }
        return isRequesterOrCreator(incident, actor);
    }

    public static boolean isRequesterOrCreator(Incident incident, Actor actor) {
        Long id = actor.id();
        return id != null && ((incident.getRequester() != null && id.equals(incident.getRequester().getId()))
                || (incident.getCreatedBy() != null && id.equals(incident.getCreatedBy().getId())));
    }

    /** Hides existence of incidents the actor may not see. */
    public static void requireView(Incident incident, Actor actor) {
        if (!canView(incident, actor)) {
            throw ApiException.notFound("Incident", incident.getId());
        }
    }

    public static void requireStaff(Actor actor) {
        if (!actor.isSystem() && !actor.isStaff()) {
            throw ApiException.forbidden("Only support staff can perform this action");
        }
    }
}

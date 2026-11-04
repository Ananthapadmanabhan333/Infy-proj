package com.nexus360.portal;

import com.nexus360.security.Actor;
import java.time.Instant;

/**
 * Extension point of {@code GET /api/portal/overview} (the employee portal): every bean implementing this interface adds
 * one section under {@link #key()}. Sections must only contain the caller's own records or organisation-wide public
 * information (employee data isolation). Keys are unique (checked at startup); sections are ordered by {@link #order()}.
 */
public interface PortalContributor {

    String key();

    /** Lower comes first. Release 3 foundations use 10–50; later modules should use 100+. */
    default int order() {
        return 100;
    }

    /** Runs inside a read-only transaction. */
    Object contribute(Context context);

    record Context(Actor actor, int limit, Instant now) {}
}

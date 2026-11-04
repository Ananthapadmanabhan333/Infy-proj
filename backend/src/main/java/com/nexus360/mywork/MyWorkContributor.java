package com.nexus360.mywork;

import com.nexus360.security.Actor;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Extension point of {@code GET /api/my-work}: every Spring bean implementing this interface adds one section to the
 * response under {@link #key()} (e.g. {@code problems}, {@code changes}, {@code alerts}, {@code onCallShifts},
 * {@code actionItems}). Later modules add a {@code @Component} in their own package; the controller never changes.
 * Keys must be unique (checked at startup); sections are ordered by {@link #order()}.
 */
public interface MyWorkContributor {

    /** JSON property name of the section. */
    String key();

    /** Lower comes first. Release 3 foundations use 10–50; later modules should use 100+. */
    default int order() {
        return 100;
    }

    /** Whether the section is shown for this user (default: staff only, as My Work itself). */
    default boolean appliesTo(Actor actor) {
        return actor.isStaff();
    }

    /** The section's value, usually a {@link Section}. Runs inside a read-only transaction. */
    Object contribute(Context context);

    /** Who is asking: the actor, the ids of the active teams they belong to, the item limit per section and the time. */
    record Context(Actor actor, Set<Long> teamIds, int limit, Instant now) {}

    /** Standard section shape: {@code total} matching items and the first {@code limit} of them. */
    record Section<T>(long total, List<T> items) {

        public static <T> Section<T> empty() {
            return new Section<>(0, List.of());
        }
    }
}

package com.nexus360.oncall;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnCallOverrideRepository extends JpaRepository<OnCallOverride, Long> {

    @EntityGraph(attributePaths = {"schedule", "schedule.team", "user", "originalUser", "createdBy"})
    Optional<OnCallOverride> findWithDetailsById(Long id);

    /** Overrides of the schedules in a status overlapping [from, to), oldest first (later ones win in the rotation maths). */
    @EntityGraph(attributePaths = {"user", "originalUser", "createdBy"})
    @Query("select o from OnCallOverride o where o.schedule.id in :ids and o.status in :statuses and o.startsAt < :to and o.endsAt > :from "
            + "order by o.createdAt asc, o.id asc")
    List<OnCallOverride> findOverlapping(@Param("ids") Collection<Long> scheduleIds, @Param("statuses") Collection<OnCallOverride.Status> statuses,
                                         @Param("from") Instant from, @Param("to") Instant to);

    /** Offers I can take: addressed to me, or open offers of schedules I take part in. */
    @EntityGraph(attributePaths = {"schedule", "schedule.team", "user", "originalUser", "createdBy"})
    @Query("select o from OnCallOverride o where o.status = 'OFFERED' and o.endsAt > :now and (o.user.id = :userId or "
            + "(o.user is null and o.schedule.id in :scheduleIds)) order by o.startsAt asc")
    List<OnCallOverride> findOffersFor(@Param("userId") Long userId, @Param("scheduleIds") Collection<Long> scheduleIds, @Param("now") Instant now);
}

package com.nexus360.oncall;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnCallUnavailabilityRepository extends JpaRepository<OnCallUnavailability, Long> {

    @EntityGraph(attributePaths = "user")
    @Query("select a from OnCallUnavailability a where a.user.id in :userIds and a.startsAt < :to and a.endsAt > :from order by a.startsAt")
    List<OnCallUnavailability> findOverlapping(@Param("userIds") Collection<Long> userIds, @Param("from") Instant from, @Param("to") Instant to);

    @EntityGraph(attributePaths = "user")
    List<OnCallUnavailability> findByUserIdAndEndsAtAfterOrderByStartsAtAsc(Long userId, Instant after);

    @EntityGraph(attributePaths = "user")
    List<OnCallUnavailability> findByEndsAtAfterOrderByStartsAtAsc(Instant after);
}

package com.nexus360.approval;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalDelegationRepository extends JpaRepository<ApprovalDelegation, Long> {

    @EntityGraph(attributePaths = {"delegator", "delegate"})
    @Query("select d from ApprovalDelegation d where (d.delegator.id = :userId or d.delegate.id = :userId) and d.revokedAt is null "
            + "and d.endsAt > :now order by d.startsAt asc")
    List<ApprovalDelegation> findCurrentAndUpcoming(@Param("userId") Long userId, @Param("now") Instant now);

    @EntityGraph(attributePaths = {"delegator", "delegate"})
    @Query("select d from ApprovalDelegation d where d.delegator.id = :userId and d.revokedAt is null and d.startsAt <= :now "
            + "and d.endsAt > :now order by d.createdAt desc")
    List<ApprovalDelegation> findActiveFor(@Param("userId") Long userId, @Param("now") Instant now);

    /** Delegators whose personal tasks the user currently covers. */
    @Query("select d.delegator.id from ApprovalDelegation d where d.delegate.id = :userId and d.revokedAt is null and d.startsAt <= :now "
            + "and d.endsAt > :now")
    List<Long> findDelegatorsCoveredBy(@Param("userId") Long userId, @Param("now") Instant now);

    @Query("select count(d) > 0 from ApprovalDelegation d where d.delegator.id = :delegatorId and d.revokedAt is null "
            + "and d.startsAt < :endsAt and d.endsAt > :startsAt")
    boolean overlaps(@Param("delegatorId") Long delegatorId, @Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt);
}

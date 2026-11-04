package com.nexus360.change;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChangeRepository extends JpaRepository<Change, Long>, JpaSpecificationExecutor<Change> {

    @Override
    @EntityGraph(attributePaths = {"assignee", "team", "requester", "approval"})
    Page<Change> findAll(Specification<Change> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"assignee", "team", "requester", "template", "approval", "problem", "pirBy", "services"})
    Optional<Change> findWithDetailsById(Long id);

    Optional<Change> findByNumberIgnoreCase(String number);

    Optional<Change> findByApprovalId(Long approvalId);

    @Query(value = "select nextval('change_number_seq')", nativeQuery = true)
    long nextNumber();

    long countByDemoTrue();

    /** Changes in the given statuses whose planned window overlaps [from, to). */
    @EntityGraph(attributePaths = {"services", "assignee", "team"})
    @Query("select distinct c from Change c where c.status in :statuses and c.plannedStart is not null and c.plannedEnd is not null "
            + "and c.plannedStart < :to and c.plannedEnd > :from order by c.plannedStart asc")
    List<Change> findOverlapping(@Param("statuses") Collection<ChangeStatus> statuses, @Param("from") Instant from, @Param("to") Instant to);

    /** My Work: active changes I implement or raised, and closed changes still waiting for their post-implementation review. */
    @EntityGraph(attributePaths = {"assignee", "team", "requester", "approval"})
    @Query("select c from Change c where (c.assignee.id = :userId or c.requester.id = :userId) and (c.status in :active "
            + "or (c.pirRequired = true and c.pirCompletedAt is null and c.status in :outcomes)) order by c.plannedStart asc nulls last, c.createdAt asc")
    List<Change> findWorkFor(@Param("userId") Long userId, @Param("active") Collection<ChangeStatus> active,
                             @Param("outcomes") Collection<ChangeStatus> outcomes, Pageable pageable);

    @Query("select count(c) from Change c where (c.assignee.id = :userId or c.requester.id = :userId) and (c.status in :active "
            + "or (c.pirRequired = true and c.pirCompletedAt is null and c.status in :outcomes))")
    long countWorkFor(@Param("userId") Long userId, @Param("active") Collection<ChangeStatus> active,
                      @Param("outcomes") Collection<ChangeStatus> outcomes);

    /** Changes in progress or approved within their window right now (services shown as MAINTENANCE). */
    @EntityGraph(attributePaths = {"services"})
    @Query("select distinct c from Change c where (c.status in :planned and c.plannedStart <= :now and c.plannedEnd > :now) "
            + "or c.status = com.nexus360.change.ChangeStatus.IMPLEMENTING")
    List<Change> findInWindow(@Param("planned") Collection<ChangeStatus> planned, @Param("now") Instant now);
}

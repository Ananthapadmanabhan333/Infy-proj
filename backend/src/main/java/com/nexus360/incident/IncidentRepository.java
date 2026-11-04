package com.nexus360.incident;

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

public interface IncidentRepository extends JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {

    @Override
    @EntityGraph(attributePaths = {"service", "department", "assignee", "requester", "parent", "team"})
    Page<Incident> findAll(Specification<Incident> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"service", "service.department", "department", "assignee", "requester", "parent",
            "createdBy", "resolvedBy", "resolutionArticle", "team"})
    Optional<Incident> findDetailedById(Long id);

    Optional<Incident> findByNumberIgnoreCase(String number);

    @EntityGraph(attributePaths = {"service", "department", "assignee", "requester", "parent", "team"})
    List<Incident> findByParentIdOrderByCreatedAtAsc(Long parentId);

    long countByParentId(Long parentId);

    @Query("select i from Incident i left join fetch i.service left join fetch i.parent "
            + "where i.status in :statuses and i.createdAt >= :since and i.id <> :excludeId")
    List<Incident> findDuplicateCandidates(@Param("statuses") Collection<IncidentStatus> statuses,
                                           @Param("since") Instant since, @Param("excludeId") Long excludeId);

    @Query("select i.parent.id, count(i) from Incident i where i.parent.id in :ids group by i.parent.id")
    List<Object[]> countChildren(@Param("ids") Collection<Long> ids);

    @Query("select i.assignee.id, count(i) from Incident i where i.assignee.id in :ids and i.status in :statuses group by i.assignee.id")
    List<Object[]> countByAssignee(@Param("ids") Collection<Long> ids, @Param("statuses") Collection<IncidentStatus> statuses);

    @Query(value = "select nextval('incident_number_seq')", nativeQuery = true)
    long nextNumber();

    long countByStatusIn(Collection<IncidentStatus> statuses);

    long countByDemoTrue();

    /** Severity rank for ORDER BY: enums are stored as text, which would otherwise sort alphabetically. */
    String PRIORITY_RANK = "case i.priority when com.nexus360.incident.Priority.CRITICAL then 0 "
            + "when com.nexus360.incident.Priority.HIGH then 1 when com.nexus360.incident.Priority.MEDIUM then 2 else 3 end";

    @EntityGraph(attributePaths = {"service", "department", "assignee", "requester", "parent", "team"})
    @Query("select i from Incident i where i.status in :statuses and i.parent is null order by " + PRIORITY_RANK + ", i.createdAt desc")
    List<Incident> findActiveByPriority(@Param("statuses") Collection<IncidentStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"service", "department", "assignee", "requester", "parent", "team"})
    @Query("select i from Incident i where i.assignee.id = :assigneeId and i.status in :statuses order by " + PRIORITY_RANK + ", i.createdAt asc")
    List<Incident> findQueueForAssignee(@Param("assigneeId") Long assigneeId, @Param("statuses") Collection<IncidentStatus> statuses,
                                        Pageable pageable);

    @EntityGraph(attributePaths = {"service", "department", "assignee", "requester", "parent", "team"})
    List<Incident> findTop10ByRequesterIdOrderByCreatedAtDesc(Long requesterId);

    long countByAssigneeIdAndStatusIn(Long assigneeId, Collection<IncidentStatus> statuses);

    /** Team queue: active top-level incidents of the given teams without an assignee, most urgent first. */
    @EntityGraph(attributePaths = {"service", "department", "assignee", "requester", "parent", "team"})
    @Query("select i from Incident i where i.team.id in :teamIds and i.assignee is null and i.parent is null and i.status in :statuses "
            + "order by " + PRIORITY_RANK + ", i.createdAt asc")
    List<Incident> findTeamQueue(@Param("teamIds") Collection<Long> teamIds, @Param("statuses") Collection<IncidentStatus> statuses,
                                 Pageable pageable);

    @Query("select count(i) from Incident i where i.team.id in :teamIds and i.assignee is null and i.parent is null and i.status in :statuses")
    long countTeamQueue(@Param("teamIds") Collection<Long> teamIds, @Param("statuses") Collection<IncidentStatus> statuses);

    /** Incidents a user reported (requester or creator) in the given statuses, newest first (employee portal). */
    @EntityGraph(attributePaths = {"service", "department", "assignee", "requester", "parent", "team"})
    @Query("select i from Incident i where (i.requester.id = :userId or i.createdBy.id = :userId) and i.status in :statuses "
            + "order by i.createdAt desc")
    List<Incident> findReportedBy(@Param("userId") Long userId, @Param("statuses") Collection<IncidentStatus> statuses, Pageable pageable);

    @Query("select count(i) from Incident i where (i.requester.id = :userId or i.createdBy.id = :userId) and i.status in :statuses")
    long countReportedBy(@Param("userId") Long userId, @Param("statuses") Collection<IncidentStatus> statuses);

    @EntityGraph(attributePaths = {"service", "department", "assignee", "requester", "parent", "team"})
    List<Incident> findTop10ByServiceIdOrderByCreatedAtDesc(Long serviceId);

    @Query("select i.service.id, i.priority, count(i) from Incident i where i.status in :statuses and i.service is not null "
            + "and i.parent is null and i.simulated = false group by i.service.id, i.priority")
    List<Object[]> countActiveByServiceAndPriority(@Param("statuses") Collection<IncidentStatus> statuses);

    /** P1/P2 incidents overlapping the window; cancelled incidents never count as downtime. */
    @Query("select i from Incident i where i.service.id is not null and i.priority in :priorities and i.parent is null "
            + "and i.createdAt < :to and (i.resolvedAt is null or i.resolvedAt > :from) "
            + "and (i.resolutionCode is null or i.resolutionCode <> com.nexus360.incident.ResolutionCode.CANCELLED)")
    List<Incident> findDowntimeContributors(@Param("priorities") Collection<Priority> priorities,
                                            @Param("from") Instant from, @Param("to") Instant to);

    @Query("select i from Incident i where i.service.id = :serviceId and i.priority in :priorities and i.parent is null "
            + "and i.createdAt < :to and (i.resolvedAt is null or i.resolvedAt > :from) "
            + "and (i.resolutionCode is null or i.resolutionCode <> com.nexus360.incident.ResolutionCode.CANCELLED)")
    List<Incident> findDowntimeContributorsForService(@Param("serviceId") Long serviceId, @Param("priorities") Collection<Priority> priorities,
                                                      @Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(i) from Incident i where i.resolutionArticle.id = :articleId")
    long countByResolutionArticle(@Param("articleId") Long articleId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update Incident i set i.resolutionArticle = null where i.resolutionArticle.id = :articleId")
    int clearResolutionArticle(@Param("articleId") Long articleId);

    /** Recently resolved (not cancelled) top-level incidents on the same service or category: the evidence pool for resolution advice. */
    @EntityGraph(attributePaths = {"service"})
    @Query("select i from Incident i left join i.service s where i.id <> :excludeId and i.parent is null and i.resolvedAt >= :since "
            + "and i.status in (com.nexus360.incident.IncidentStatus.RESOLVED, com.nexus360.incident.IncidentStatus.CLOSED) "
            + "and (i.resolutionCode is null or i.resolutionCode <> com.nexus360.incident.ResolutionCode.CANCELLED) "
            + "and (s.id = :serviceId or i.category = :category) order by i.resolvedAt desc")
    List<Incident> findResolvedPool(@Param("excludeId") Long excludeId, @Param("serviceId") Long serviceId,
                                    @Param("category") Category category, @Param("since") Instant since, Pageable pageable);

    /** Other active top-level incidents on a service, most urgent first. */
    @EntityGraph(attributePaths = {"service", "department", "assignee", "requester", "parent", "team"})
    @Query("select i from Incident i where i.service.id = :serviceId and i.id not in :excluded and i.parent is null "
            + "and i.status in :statuses order by " + PRIORITY_RANK + ", i.createdAt desc")
    List<Incident> findRelatedActive(@Param("serviceId") Long serviceId, @Param("excluded") Collection<Long> excluded,
                                     @Param("statuses") Collection<IncidentStatus> statuses, Pageable pageable);
}

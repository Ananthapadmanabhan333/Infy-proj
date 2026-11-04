package com.nexus360.problem;

import com.nexus360.incident.Incident;
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

public interface ProblemRepository extends JpaRepository<Problem, Long>, JpaSpecificationExecutor<Problem> {

    @Override
    @EntityGraph(attributePaths = {"owner", "team"})
    Page<Problem> findAll(Specification<Problem> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "team", "department", "kbArticle", "createdBy", "services"})
    Optional<Problem> findWithDetailsById(Long id);

    Optional<Problem> findByNumberIgnoreCase(String number);

    @Query(value = "select nextval('problem_number_seq')", nativeQuery = true)
    long nextNumber();

    long countByDemoTrue();

    /** Active problems owned by the user or unowned in their teams (My Work). */
    @EntityGraph(attributePaths = {"owner", "team"})
    @Query("select p from Problem p left join p.team t where p.status in :statuses and (p.owner.id = :userId or (p.owner is null and t.id in :teamIds)) "
            + "order by p.priority asc, p.createdAt asc")
    List<Problem> findWorkFor(@Param("userId") Long userId, @Param("teamIds") Collection<Long> teamIds,
                              @Param("statuses") Collection<ProblemStatus> statuses, Pageable pageable);

    @Query("select count(p) from Problem p left join p.team t where p.status in :statuses and (p.owner.id = :userId or (p.owner is null and t.id in :teamIds))")
    long countWorkFor(@Param("userId") Long userId, @Param("teamIds") Collection<Long> teamIds, @Param("statuses") Collection<ProblemStatus> statuses);

    /** Top-level, non-cancelled incidents created since {@code since} that are not linked to any problem (recurring detection). */
    @Query("select i from Incident i left join fetch i.service where i.createdAt >= :since and i.parent is null "
            + "and (i.resolutionCode is null or i.resolutionCode <> com.nexus360.incident.ResolutionCode.CANCELLED) "
            + "and not exists (select 1 from ProblemIncident pi where pi.id.incidentId = i.id) order by i.createdAt desc")
    List<Incident> findUnlinkedIncidentsSince(@Param("since") Instant since, Pageable pageable);
}

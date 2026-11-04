package com.nexus360.problem;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProblemIncidentRepository extends JpaRepository<ProblemIncident, ProblemIncident.Key> {

    @Query("select pi.id.incidentId from ProblemIncident pi where pi.id.problemId = :problemId order by pi.createdAt asc")
    List<Long> findIncidentIds(@Param("problemId") Long problemId);

    @Query("select pi from ProblemIncident pi where pi.id.incidentId = :incidentId")
    Optional<ProblemIncident> findByIncident(@Param("incidentId") Long incidentId);

    @Query("select pi.id.problemId, count(pi) from ProblemIncident pi where pi.id.problemId in :ids group by pi.id.problemId")
    List<Object[]> countByProblems(@Param("ids") Collection<Long> ids);
}

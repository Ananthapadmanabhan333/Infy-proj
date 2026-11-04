package com.nexus360.problem;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProblemActionRepository extends JpaRepository<ProblemAction, Long> {

    @EntityGraph(attributePaths = {"owner"})
    List<ProblemAction> findByProblemIdOrderByCreatedAtAscIdAsc(Long problemId);

    long countByProblemIdAndStatusIn(Long problemId, Collection<ProblemAction.Status> statuses);

    @Query("select a.problem.id, count(a) from ProblemAction a where a.problem.id in :ids and a.status in :statuses group by a.problem.id")
    List<Object[]> countOpenByProblems(@Param("ids") Collection<Long> ids, @Param("statuses") Collection<ProblemAction.Status> statuses);
}

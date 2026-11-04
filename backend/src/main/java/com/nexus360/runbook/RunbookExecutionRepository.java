package com.nexus360.runbook;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RunbookExecutionRepository extends JpaRepository<RunbookExecution, Long>, JpaSpecificationExecutor<RunbookExecution> {

    @Override
    @EntityGraph(attributePaths = {"runbook", "incident", "startedBy"})
    Page<RunbookExecution> findAll(Specification<RunbookExecution> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"runbook", "incident", "startedBy"})
    List<RunbookExecution> findTop100ByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"runbook", "incident", "startedBy"})
    List<RunbookExecution> findByIncidentIdOrderByCreatedAtDesc(Long incidentId);

    @EntityGraph(attributePaths = {"runbook", "incident", "startedBy"})
    List<RunbookExecution> findByRunbookIdOrderByCreatedAtDesc(Long runbookId);

    @EntityGraph(attributePaths = {"runbook", "incident", "startedBy"})
    Optional<RunbookExecution> findWithDetailsById(Long id);
}

package com.nexus360.workflow;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, Long> {

    long countByWorkflowIdAndStartedAtAfter(Long workflowId, Instant since);

    long countByWorkflowIdAndStatusAndStartedAtAfter(Long workflowId, WorkflowExecution.Status status, Instant since);

    long countByStartedAtAfter(Instant since);

    long countByStatus(WorkflowExecution.Status status);

    long countByStatusAndStartedAtAfter(WorkflowExecution.Status status, Instant since);

    @Query("select max(e.startedAt) from WorkflowExecution e where e.workflow.id = :workflowId")
    Instant lastExecutedAt(@Param("workflowId") Long workflowId);

    @EntityGraph(attributePaths = "workflow")
    Page<WorkflowExecution> findAllByOrderByStartedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = "workflow")
    Page<WorkflowExecution> findByWorkflowIdOrderByStartedAtDesc(Long workflowId, Pageable pageable);

    @EntityGraph(attributePaths = "workflow")
    Page<WorkflowExecution> findByStatusOrderByStartedAtDesc(WorkflowExecution.Status status, Pageable pageable);

    @EntityGraph(attributePaths = "workflow")
    Page<WorkflowExecution> findByWorkflowIdAndStatusOrderByStartedAtDesc(Long workflowId, WorkflowExecution.Status status, Pageable pageable);

    @Query("select e.id from WorkflowExecution e where e.status = com.nexus360.workflow.WorkflowExecution.Status.RETRY_SCHEDULED "
            + "and e.nextAttemptAt <= :now order by e.nextAttemptAt asc")
    List<Long> findDueRetries(@Param("now") Instant now, Pageable pageable);

    /** Atomically claims a scheduled retry so that only one worker runs it. */
    @Modifying
    @Query("update WorkflowExecution e set e.status = com.nexus360.workflow.WorkflowExecution.Status.RUNNING, e.attempt = e.attempt + 1 "
            + "where e.id = :id and e.status = com.nexus360.workflow.WorkflowExecution.Status.RETRY_SCHEDULED")
    int claimRetry(@Param("id") Long id);
}

package com.nexus360.workflow;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, Long> {

    Optional<WorkflowVersion> findByWorkflowIdAndVersionNumber(Long workflowId, int versionNumber);

    List<WorkflowVersion> findByWorkflowIdOrderByVersionNumberDesc(Long workflowId);
}

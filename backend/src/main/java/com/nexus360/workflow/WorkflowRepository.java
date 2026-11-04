package com.nexus360.workflow;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    List<Workflow> findByTriggerEventAndActiveTrue(WorkflowModel.Trigger trigger);

    List<Workflow> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    long countByActiveTrue();
}

package com.nexus360.approval;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ApprovalTaskRepository extends JpaRepository<ApprovalTask, Long>, JpaSpecificationExecutor<ApprovalTask> {

    @EntityGraph(attributePaths = {"approverUser", "approverTeam", "decidedBy", "delegatedFrom", "stage"})
    List<ApprovalTask> findByApprovalIdOrderByIdAsc(Long approvalId);

    @EntityGraph(attributePaths = {"approverUser", "approverTeam", "decidedBy", "delegatedFrom", "stage"})
    List<ApprovalTask> findByApprovalIdIn(Collection<Long> approvalIds);

    @EntityGraph(attributePaths = {"approverUser", "approverTeam", "decidedBy", "delegatedFrom"})
    List<ApprovalTask> findByStageIdOrderBySeqAsc(Long stageId);

    @Override
    @EntityGraph(attributePaths = {"approval", "approval.requestedBy", "stage", "approverUser", "approverTeam", "decidedBy", "delegatedFrom"})
    Page<ApprovalTask> findAll(Specification<ApprovalTask> spec, Pageable pageable);
}

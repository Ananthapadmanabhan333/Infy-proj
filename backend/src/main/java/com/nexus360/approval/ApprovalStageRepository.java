package com.nexus360.approval;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalStageRepository extends JpaRepository<ApprovalStage, Long> {

    List<ApprovalStage> findByApprovalIdOrderByStageOrderAsc(Long approvalId);

    List<ApprovalStage> findByApprovalIdInOrderByStageOrderAsc(Collection<Long> approvalIds);

    /** Active stages past their deadline that were not escalated yet (pending approvals only). */
    @Query("select s.id from ApprovalStage s where s.status = com.nexus360.approval.ApprovalModel.StageStatus.ACTIVE "
            + "and s.escalatedAt is null and s.dueAt is not null and s.dueAt <= :now "
            + "and s.approval.status = com.nexus360.approval.Approval.Status.PENDING order by s.dueAt asc")
    List<Long> findOverdue(@Param("now") Instant now, Pageable pageable);

    /** Claims an overdue stage for escalation exactly once (idempotent across runs and instances). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApprovalStage s set s.escalatedAt = :now where s.id = :id and s.escalatedAt is null")
    int claimEscalation(@Param("id") Long id, @Param("now") Instant now);
}

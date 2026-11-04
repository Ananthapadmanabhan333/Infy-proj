package com.nexus360.approval;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalEventRepository extends JpaRepository<ApprovalEvent, Long> {

    List<ApprovalEvent> findByApprovalIdOrderByCreatedAtAscIdAsc(Long approvalId);
}

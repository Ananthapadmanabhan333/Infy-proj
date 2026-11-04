package com.nexus360.approval;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalFlowRepository extends JpaRepository<ApprovalFlow, Long> {

    Optional<ApprovalFlow> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<ApprovalFlow> findAllByOrderByNameAsc();
}

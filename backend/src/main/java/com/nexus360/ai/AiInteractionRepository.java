package com.nexus360.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiInteractionRepository extends JpaRepository<AiInteraction, Long> {

    Optional<AiInteraction> findFirstBySourceAndSuccessFalseOrderByCreatedAtDesc(String source);

    List<AiInteraction> findByUserIdAndSuccessTrueOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
}

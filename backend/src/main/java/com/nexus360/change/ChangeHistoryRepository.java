package com.nexus360.change;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeHistoryRepository extends JpaRepository<ChangeHistory, Long> {

    List<ChangeHistory> findByChangeIdOrderByCreatedAtAscIdAsc(Long changeId);
}

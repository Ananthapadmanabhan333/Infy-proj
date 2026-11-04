package com.nexus360.incident;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentHistoryRepository extends JpaRepository<IncidentHistory, Long> {

    List<IncidentHistory> findByIncidentIdOrderByCreatedAtAscIdAsc(Long incidentId);
}

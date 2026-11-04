package com.nexus360.incident;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentEscalationRepository extends JpaRepository<IncidentEscalation, Long> {

    List<IncidentEscalation> findByIncidentIdOrderByCreatedAtAscIdAsc(Long incidentId);

    boolean existsByIncidentSlaIdAndLevel(Long incidentSlaId, int level);

    long countByIncidentSlaId(Long incidentSlaId);
}

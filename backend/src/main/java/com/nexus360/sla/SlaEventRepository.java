package com.nexus360.sla;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaEventRepository extends JpaRepository<SlaEvent, Long> {

    List<SlaEvent> findByIncidentSlaIdOrderByCreatedAtAscIdAsc(Long incidentSlaId);
}

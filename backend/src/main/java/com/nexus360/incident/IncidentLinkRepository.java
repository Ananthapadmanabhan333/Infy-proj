package com.nexus360.incident;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentLinkRepository extends JpaRepository<IncidentLink, Long> {

    @Query("select l from IncidentLink l join fetch l.source join fetch l.target where l.source.id = :id or l.target.id = :id")
    List<IncidentLink> findAllInvolving(@Param("id") Long incidentId);

    boolean existsBySourceIdAndTargetIdAndLinkType(Long sourceId, Long targetId, LinkType linkType);
}

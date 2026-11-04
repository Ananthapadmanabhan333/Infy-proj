package com.nexus360.major;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MajorIncidentRepository extends JpaRepository<MajorIncident, Long> {

    @EntityGraph(attributePaths = {"incident", "incident.service", "commander", "techLead", "commsLead", "declaredBy", "services"})
    Optional<MajorIncident> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"incident", "commander", "techLead", "commsLead"})
    Optional<MajorIncident> findByIncidentId(Long incidentId);

    boolean existsByIncidentId(Long incidentId);

    @EntityGraph(attributePaths = {"incident", "commander", "techLead", "commsLead"})
    @Query("select m from MajorIncident m where m.status in :statuses and (:search = '' or "
            + "lower(m.number) like :search or lower(m.incident.number) like :search or lower(m.incident.title) like :search)")
    Page<MajorIncident> search(@Param("statuses") List<MajorIncident.Status> statuses, @Param("search") String search, Pageable page);

    @Query(value = "select nextval('major_incident_number_seq')", nativeQuery = true)
    long nextNumber();

    long countByDemoTrue();
}

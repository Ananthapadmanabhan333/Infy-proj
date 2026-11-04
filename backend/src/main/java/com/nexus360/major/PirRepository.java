package com.nexus360.major;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PirRepository extends JpaRepository<PostIncidentReview, Long> {

    @EntityGraph(attributePaths = {"incident", "incident.service", "owner", "approvedBy"})
    Optional<PostIncidentReview> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"incident", "owner"})
    Optional<PostIncidentReview> findByIncidentId(Long incidentId);

    boolean existsByIncidentId(Long incidentId);

    @EntityGraph(attributePaths = {"incident", "owner"})
    @Query("select p from PostIncidentReview p where p.status in :statuses and (:search = '' or "
            + "lower(p.number) like :search or lower(p.title) like :search or lower(p.incident.number) like :search)")
    Page<PostIncidentReview> search(@Param("statuses") List<PostIncidentReview.Status> statuses, @Param("search") String search, Pageable page);

    @Query(value = "select nextval('pir_number_seq')", nativeQuery = true)
    long nextNumber();

    long countByDemoTrue();
}

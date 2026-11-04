package com.nexus360.alert;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

    @Override
    @EntityGraph(attributePaths = {"source", "service", "ci", "team", "incident", "acknowledgedBy"})
    Page<Alert> findAll(Specification<Alert> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"source", "integration", "service", "ci", "team", "incident", "acknowledgedBy", "resolvedBy", "routingRule",
            "suppressionRule"})
    Optional<Alert> findWithDetailsById(Long id);

    /** The active alert of a scope and fingerprint (at most one, enforced by a partial unique index). */
    @EntityGraph(attributePaths = {"source", "service", "ci", "team", "incident"})
    @Query("select a from Alert a where a.scopeKey = :scope and a.fingerprint = :fingerprint and a.status in :statuses")
    Optional<Alert> findActive(@Param("scope") String scope, @Param("fingerprint") String fingerprint, @Param("statuses") Collection<Alert.Status> statuses);

    /** Resolved alerts of a scope and fingerprint resolved at or after {@code since}, newest first. */
    @EntityGraph(attributePaths = {"source", "service", "ci", "team", "incident"})
    @Query("select a from Alert a where a.scopeKey = :scope and a.fingerprint = :fingerprint and a.status = com.nexus360.alert.Alert.Status.RESOLVED "
            + "and a.resolvedAt >= :since order by a.resolvedAt desc")
    List<Alert> findRecentlyResolved(@Param("scope") String scope, @Param("fingerprint") String fingerprint, @Param("since") Instant since);

    @EntityGraph(attributePaths = {"source", "service", "ci", "team", "incident", "acknowledgedBy"})
    @Query("select a from Alert a where a.groupKey = :groupKey and a.id <> :id order by a.lastSeenAt desc")
    List<Alert> findGroupSiblings(@Param("groupKey") String groupKey, @Param("id") Long id, Pageable pageable);

    @EntityGraph(attributePaths = {"source", "service", "ci", "team", "incident", "acknowledgedBy"})
    @Query("select a from Alert a where a.incident.id = :incidentId order by a.firstSeenAt")
    List<Alert> findByIncidentId(@Param("incidentId") Long incidentId);

    @Query(value = "select nextval('alert_number_seq')", nativeQuery = true)
    long nextNumber();

    long countByDemoTrue();
}

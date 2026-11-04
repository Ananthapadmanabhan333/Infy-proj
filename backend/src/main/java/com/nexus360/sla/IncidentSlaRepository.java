package com.nexus360.sla;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentSlaRepository extends JpaRepository<IncidentSla, Long> {

    @EntityGraph(attributePaths = {"calendar", "calendar.holidays"})
    Optional<IncidentSla> findByIncidentId(Long incidentId);

    @EntityGraph(attributePaths = "calendar")
    List<IncidentSla> findByIncidentIdIn(Collection<Long> incidentIds);

    @EntityGraph(attributePaths = {"incident", "incident.department", "incident.assignee", "calendar", "calendar.holidays"})
    List<IncidentSla> findByClockStatus(IncidentSla.ClockStatus status);

    @Query(value = "select s from IncidentSla s where s.clockStatus <> com.nexus360.sla.IncidentSla.ClockStatus.STOPPED "
            + "order by s.resolutionDueAt asc nulls last",
            countQuery = "select count(s) from IncidentSla s where s.clockStatus <> com.nexus360.sla.IncidentSla.ClockStatus.STOPPED")
    Page<IncidentSla> findAllActive(Pageable pageable);

    @Query(value = "select s from IncidentSla s where s.clockStatus = :clock and s.health = :health order by s.resolutionDueAt asc nulls last",
            countQuery = "select count(s) from IncidentSla s where s.clockStatus = :clock and s.health = :health")
    Page<IncidentSla> findByClockAndHealth(@Param("clock") IncidentSla.ClockStatus clock, @Param("health") IncidentSla.Health health,
                                           Pageable pageable);

    @Query(value = "select s from IncidentSla s where s.clockStatus = com.nexus360.sla.IncidentSla.ClockStatus.PAUSED order by s.pausedAt asc",
            countQuery = "select count(s) from IncidentSla s where s.clockStatus = com.nexus360.sla.IncidentSla.ClockStatus.PAUSED")
    Page<IncidentSla> findPaused(Pageable pageable);

    long countByClockStatusNot(IncidentSla.ClockStatus status);

    long countByClockStatus(IncidentSla.ClockStatus status);

    long countByClockStatusNotAndHealth(IncidentSla.ClockStatus status, IncidentSla.Health health);

    @Query("select s from IncidentSla s join fetch s.incident i left join fetch i.service where s.completedAt >= :from and s.outcome in "
            + "(com.nexus360.sla.IncidentSla.Outcome.MET, com.nexus360.sla.IncidentSla.Outcome.MISSED)")
    List<IncidentSla> findCompletedSince(@Param("from") Instant from);
}

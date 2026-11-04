package com.nexus360.report;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportScheduleRepository extends JpaRepository<ReportSchedule, Long> {

    @EntityGraph(attributePaths = {"owner"})
    @Query("select s from ReportSchedule s order by s.name, s.id")
    List<ReportSchedule> findAllOrdered();

    @EntityGraph(attributePaths = {"owner"})
    @Query("select s from ReportSchedule s where s.owner.id = :ownerId order by s.name, s.id")
    List<ReportSchedule> findByOwner(@Param("ownerId") Long ownerId);

    @EntityGraph(attributePaths = {"owner"})
    Optional<ReportSchedule> findWithOwnerById(Long id);

    /** Due active schedules, locked; rows locked by another transaction are skipped. */
    @Query(value = "select * from report_schedules where active and next_run_at <= :now order by next_run_at, id limit :limit "
            + "for update skip locked", nativeQuery = true)
    List<ReportSchedule> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    long countByDemoTrue();
}

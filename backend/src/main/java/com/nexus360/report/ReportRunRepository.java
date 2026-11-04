package com.nexus360.report;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportRunRepository extends JpaRepository<ReportRun, Long> {

    /** Run metadata without the file bytes, newest first. */
    @Query("select new com.nexus360.report.ReportRunDto(r.id, r.schedule.id, r.trigger, r.scheduledFor, r.status, r.periodFrom, "
            + "r.periodTo, r.fileName, r.contentType, r.sizeBytes, r.error, r.emailedTo, r.startedAt, r.finishedAt, r.demo) "
            + "from ReportRun r where r.schedule.id = :scheduleId order by r.startedAt desc, r.id desc")
    List<ReportRunDto> listForSchedule(@Param("scheduleId") Long scheduleId, Pageable pageable);

    @Query("select new com.nexus360.report.ReportRunDto(r.id, r.schedule.id, r.trigger, r.scheduledFor, r.status, r.periodFrom, "
            + "r.periodTo, r.fileName, r.contentType, r.sizeBytes, r.error, r.emailedTo, r.startedAt, r.finishedAt, r.demo) "
            + "from ReportRun r where r.id = :id")
    java.util.Optional<ReportRunDto> findDto(@Param("id") Long id);

    @Query("select r.id from ReportRun r where r.schedule.id = :scheduleId order by r.startedAt desc, r.id desc")
    List<Long> idsNewestFirst(@Param("scheduleId") Long scheduleId);

    @Modifying
    @Query("delete from ReportRun r where r.id in :ids")
    int deleteByIds(@Param("ids") List<Long> ids);

    /** Runs left RUNNING by an instance that stopped mid-generation. */
    @Modifying
    @Query("update ReportRun r set r.status = com.nexus360.report.ReportRun.Status.FAILED, r.finishedAt = :now, "
            + "r.error = 'The run was interrupted (the API stopped while generating)' "
            + "where r.status = com.nexus360.report.ReportRun.Status.RUNNING and r.startedAt < :cutoff")
    int failStale(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}

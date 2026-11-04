package com.nexus360.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** One execution of a schedule; a successful run keeps the generated file for download. */
@Getter
@Setter
@Entity
@Table(name = "report_runs")
public class ReportRun {

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    public enum Trigger { SCHEDULE, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id")
    private ReportSchedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 10)
    private Trigger trigger;

    /** The due time this run covers (scheduled runs; unique per schedule). */
    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "triggered_by_id")
    private Long triggeredById;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "file_name", length = 200)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "file_data")
    private byte[] fileData;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 2000)
    private String error;

    @Column(name = "emailed_to", nullable = false)
    private int emailedTo;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;
}

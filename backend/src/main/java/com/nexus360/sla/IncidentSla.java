package com.nexus360.sla;

import com.nexus360.common.BaseEntity;
import com.nexus360.incident.Incident;
import com.nexus360.incident.Priority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * SLA clock of one incident. The resolution clock accumulates business seconds in {@code consumedSeconds}
 * for finished running segments; the current segment started at {@code runningSince}.
 */
@Getter
@Setter
@Entity
@Table(name = "incident_slas")
public class IncidentSla extends BaseEntity {

    public enum ClockStatus { RUNNING, PAUSED, STOPPED }

    public enum Health { ON_TRACK, AT_RISK, BREACHED }

    public enum Outcome { MET, MISSED, CANCELLED }

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", unique = true)
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id")
    private SlaPolicy policy;

    @Column(name = "policy_name", nullable = false, length = 120)
    private String policyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority;

    @Column(name = "business_hours_only", nullable = false)
    private boolean businessHoursOnly;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_id")
    private BusinessHoursCalendar calendar;

    @Column(name = "response_target_minutes", nullable = false)
    private int responseTargetMinutes;

    @Column(name = "resolution_target_minutes", nullable = false)
    private int resolutionTargetMinutes;

    @Column(name = "at_risk_percent", nullable = false)
    private int atRiskPercent;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "response_due_at", nullable = false)
    private Instant responseDueAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "response_breached", nullable = false)
    private boolean responseBreached;

    @Column(name = "consumed_seconds", nullable = false)
    private long consumedSeconds;

    @Column(name = "running_since")
    private Instant runningSince;

    @Column(name = "resolution_due_at")
    private Instant resolutionDueAt;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "total_paused_seconds", nullable = false)
    private long totalPausedSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "clock_status", nullable = false, length = 10)
    private ClockStatus clockStatus = ClockStatus.RUNNING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Health health = Health.ON_TRACK;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Outcome outcome;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "at_risk_at")
    private Instant atRiskAt;

    @Column(name = "breached_at")
    private Instant breachedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    public long resolutionTargetSeconds() {
        return resolutionTargetMinutes * 60L;
    }
}

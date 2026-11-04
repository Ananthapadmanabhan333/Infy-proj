package com.nexus360.oncall;

import com.nexus360.team.Team;
import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;

/** An on-call schedule of one team (rotation maths in {@link Rotation}; participants in {@link OnCallParticipant}). */
@Getter
@Setter
@Entity
@Table(name = "oncall_schedules")
public class OnCallSchedule extends com.nexus360.common.BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "time_zone", nullable = false, length = 60)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(name = "rotation_type", nullable = false, length = 10)
    private Rotation.Type rotationType;

    @Column(name = "handoff_time", nullable = false)
    private LocalTime handoffTime = LocalTime.of(9, 0);

    /** ISO day of week (1 = Monday), WEEKLY only. */
    @Column(name = "handoff_day")
    private Integer handoffDay;

    @Column(name = "shift_hours")
    private Integer shiftHours;

    @Column(name = "rotation_start", nullable = false)
    private LocalDate rotationStart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalation_policy_id")
    private EscalationPolicy escalationPolicy;

    @Column(name = "holiday_calendar_id")
    private Long holidayCalendarId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holiday_cover_user_id")
    private User holidayCoverUser;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Column(name = "created_by_id")
    private Long createdById;

    @Version
    private long version;
}

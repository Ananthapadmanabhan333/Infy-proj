package com.nexus360.oncall;

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
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Cover for part of a schedule layer. {@code ACTIVE} overrides replace the rotation; {@code OFFERED} ones wait for {@link #user}
 * (or, when it is null, any participant of the schedule) to take them.
 */
@Getter
@Setter
@Entity
@Table(name = "oncall_overrides")
public class OnCallOverride extends com.nexus360.common.BaseEntity {

    public enum Status { OFFERED, ACTIVE, DECLINED, CANCELLED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id")
    private OnCallSchedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Rotation.Layer layer = Rotation.Layer.PRIMARY;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_user_id")
    private User originalUser;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "taken_at")
    private Instant takenAt;

    @Column(name = "decided_note", length = 500)
    private String decidedNote;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Version
    private long version;
}

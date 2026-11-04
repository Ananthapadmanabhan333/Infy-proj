package com.nexus360.major;

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
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/** A follow-up of a major incident ({@link #majorIncidentId}) or a corrective/preventive action of a review ({@link #pirId}). */
@Getter
@Setter
@Entity
@Table(name = "incident_action_items")
public class ActionItem extends com.nexus360.common.BaseEntity {

    public enum Type { CORRECTIVE, PREVENTIVE, FOLLOW_UP }

    public enum Status {
        OPEN, IN_PROGRESS, DONE, CANCELLED;

        public static final Set<Status> OPEN_STATES = EnumSet.of(OPEN, IN_PROGRESS);
    }

    @Column(name = "major_incident_id")
    private Long majorIncidentId;

    @Column(name = "pir_id")
    private Long pirId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 12)
    private Type actionType = Type.FOLLOW_UP;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.OPEN;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Version
    private long version;
}

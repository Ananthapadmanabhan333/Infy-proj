package com.nexus360.problem;

import com.nexus360.common.BaseEntity;
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
import lombok.Getter;
import lombok.Setter;

/** Corrective or preventive action of a problem (owner, due date, status). */
@Getter
@Setter
@Entity
@Table(name = "problem_actions")
public class ProblemAction extends BaseEntity {

    public enum Type { CORRECTIVE, PREVENTIVE }

    public enum Status { OPEN, IN_PROGRESS, DONE, CANCELLED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 12)
    private Type actionType = Type.CORRECTIVE;

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

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Version
    private long version;
}

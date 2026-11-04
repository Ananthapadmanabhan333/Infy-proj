package com.nexus360.approval;

import com.nexus360.approval.ApprovalModel.TaskStatus;
import com.nexus360.team.Team;
import com.nexus360.user.Role;
import com.nexus360.user.User;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** One approver's decision slot within a stage: a user, anyone with a role, or any member of a team. */
@Getter
@Setter
@Entity
@Table(name = "approval_tasks")
public class ApprovalTask {

    /** Stored approver kinds (MANAGER is resolved to USER when the stage starts). */
    public enum Kind { USER, ROLE, TEAM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approval_id")
    private Approval approval;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id")
    private ApprovalStage stage;

    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_kind", nullable = false, length = 10)
    private Kind approverKind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_user_id")
    private User approverUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_role", length = 20)
    private Role approverRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_team_id")
    private Team approverTeam;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskStatus status = TaskStatus.WAITING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_id")
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(length = 1000)
    private String comment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delegated_from_id")
    private User delegatedFrom;

    @Column(name = "delegated_at")
    private Instant delegatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

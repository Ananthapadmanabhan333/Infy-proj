package com.nexus360.approval;

import com.nexus360.approval.ApprovalModel.EscalationDef;
import com.nexus360.approval.ApprovalModel.Quorum;
import com.nexus360.approval.ApprovalModel.StageMode;
import com.nexus360.approval.ApprovalModel.StageStatus;
import com.nexus360.workflow.WorkflowModel;
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
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One stage of one approval (instantiated from a flow's stage definition). */
@Getter
@Setter
@Entity
@Table(name = "approval_stages")
public class ApprovalStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approval_id")
    private Approval approval;

    @Column(name = "stage_order", nullable = false)
    private int stageOrder;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StageMode mode = StageMode.PARALLEL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Quorum quorum = Quorum.ANY;

    @Column(name = "required_count", nullable = false)
    private int requiredCount = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stage_condition", columnDefinition = "jsonb")
    private WorkflowModel.Conditions condition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StageStatus status = StageStatus.WAITING;

    @Column(name = "deadline_hours")
    private Integer deadlineHours;

    @Column(name = "due_at")
    private Instant dueAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private EscalationDef escalation;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;
}

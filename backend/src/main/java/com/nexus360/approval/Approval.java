package com.nexus360.approval;

import com.nexus360.common.CreatedEntity;
import com.nexus360.user.Role;
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
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Human approval gate for sensitive actions: service requests, gated runbooks, sensitive runbook steps and AI-proposed changes. */
@Getter
@Setter
@Entity
@Table(name = "approvals")
public class Approval extends CreatedEntity {

    /**
     * Subject types. Release 3 adds {@code CHANGE} (change approval / CAB), {@code PIR} (post-incident reviews, used by the
     * major-incident module) and {@code WORKFLOW} (sign-off requested by the workflow action REQUEST_APPROVAL on an incident
     * or service request; {@code payload.subjectKind} names which).
     */
    public enum Type { SERVICE_REQUEST, RUNBOOK_EXECUTION, RUNBOOK_STEP, AI_ACTION, CHANGE, PIR, WORKFLOW, PLAYBOOK, VULNERABILITY_ACCEPTANCE }

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_type", nullable = false, length = 30)
    private Type type;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column(name = "reference_number", length = 30)
    private String referenceNumber;

    @Column(nullable = false, length = 250)
    private String title;

    @Column(columnDefinition = "text")
    private String details;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_id")
    private User requestedBy;

    /** Primary routing role (single-step approvals; for flows the first role approver of the first stage, else null). */
    @Enumerated(EnumType.STRING)
    @Column(name = "approver_role", length = 20)
    private Role approverRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_id")
    private User decidedBy;

    @Column(name = "decision_comment", length = 1000)
    private String decisionComment;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    // ------------------------------------------------------------------ approval engine v2 (denormalised for lists)

    @Column(name = "flow_id")
    private Long flowId;

    @Column(name = "flow_name", length = 120)
    private String flowName;

    /** Order of the active stage (the last decided one once finished). */
    @Column(name = "current_stage")
    private Integer currentStage;

    @Column(name = "stage_count", nullable = false)
    private int stageCount = 1;

    /** Deadline of the active stage, if it has one. */
    @Column(name = "due_at")
    private Instant dueAt;

    /** App-relative link to the subject (e.g. {@code /changes/12}). */
    @Column(length = 300)
    private String link;

    /** Subject facts the stage conditions were evaluated against. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> facts = new java.util.LinkedHashMap<>();

    @Version
    private long version;
}

package com.nexus360.runbook;

import com.nexus360.common.BaseEntity;
import com.nexus360.incident.Incident;
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
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "runbook_executions")
public class RunbookExecution extends BaseEntity {

    public enum Status { PENDING_APPROVAL, READY, IN_PROGRESS, COMPLETED, REJECTED, ABORTED }

    /**
     * {@code approvalStatus}: NOT_REQUIRED (ordinary step, or a sensitive step whose completion has not been requested),
     * PENDING (completion awaiting an incident manager), APPROVED or REJECTED.
     */
    public record StepState(int order, String title, boolean done, String notes, String completedByName, Instant completedAt,
                            boolean requiresApproval, String approvalStatus, Long approvalId) {

        public static final String NOT_REQUIRED = "NOT_REQUIRED";
        public static final String PENDING = "PENDING";
        public static final String APPROVED = "APPROVED";
        public static final String REJECTED = "REJECTED";

        public StepState {
            approvalStatus = approvalStatus == null ? NOT_REQUIRED : approvalStatus;
        }

        public StepState(int order, String title, boolean done, String notes, String completedByName, Instant completedAt) {
            this(order, title, done, notes, completedByName, completedAt, false, NOT_REQUIRED, null);
        }
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "runbook_id")
    private Runbook runbook;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by_id")
    private User startedBy;

    @Column(name = "approval_id")
    private Long approvalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "step_states", nullable = false, columnDefinition = "jsonb")
    private List<StepState> stepStates = new ArrayList<>();

    @Column(name = "outcome_notes", columnDefinition = "text")
    private String outcomeNotes;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Version
    private long version;
}

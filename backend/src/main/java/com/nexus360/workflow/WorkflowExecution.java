package com.nexus360.workflow;

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
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "workflow_executions")
public class WorkflowExecution {

    public enum Status { RUNNING, SUCCEEDED, FAILED, RETRY_SCHEDULED, RATE_LIMITED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id")
    private Workflow workflow;

    @Column(name = "workflow_version", nullable = false)
    private int workflowVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_event", nullable = false, length = 30)
    private WorkflowModel.Trigger triggerEvent;

    @Column(name = "incident_id")
    private Long incidentId;

    @Column(name = "service_request_id")
    private Long serviceRequestId;

    /** Triggering change (Release 3, CHANGE_REQUEST_CREATED). */
    @Column(name = "change_id")
    private Long changeId;

    /** Release 3: the alert of an ALERT_RECEIVED execution. */
    @Column(name = "alert_id")
    private Long alertId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<WorkflowModel.StepLog> steps = new ArrayList<>();

    @Column(nullable = false)
    private int depth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private WorkflowModel.Branch branch = WorkflowModel.Branch.YES;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}

package com.nexus360.workflow;

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
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "workflows")
public class Workflow extends BaseEntity {

    @Column(nullable = false, length = 150, unique = true)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_event", nullable = false, length = 30)
    private WorkflowModel.Trigger triggerEvent;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "current_version", nullable = false)
    private int currentVersion = 1;

    @Column(name = "max_executions_per_hour", nullable = false)
    private int maxExecutionsPerHour = 100;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "retry_backoff_seconds", nullable = false)
    private int retryBackoffSeconds = 30;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /** Last time the SCHEDULED trigger processed this workflow. */
    @Column(name = "last_scheduled_run_at")
    private java.time.Instant lastScheduledRunAt;

    @Version
    private long version;
}

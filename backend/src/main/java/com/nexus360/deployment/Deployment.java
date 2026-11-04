package com.nexus360.deployment;

import com.nexus360.change.Change;
import com.nexus360.cmdb.BusinessService;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** One deployment of a service version to an environment (manual record or reported by a CI/CD webhook). */
@Getter
@Setter
@Entity
@Table(name = "deployments")
public class Deployment {

    public enum Environment { DEV, TEST, STAGING, PRODUCTION }

    public enum Status { IN_PROGRESS, SUCCEEDED, FAILED, ROLLED_BACK }

    public enum Source { MANUAL, GITHUB, GITLAB, GENERIC }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, unique = true, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_id")
    private Release release;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "change_id")
    private Change change;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id")
    private BusinessService service;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Environment environment;

    @Column(nullable = false, length = 80)
    private String version;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "pipeline_url", length = 500)
    private String pipelineUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.IN_PROGRESS;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source = Source.MANUAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    private DeploymentSource webhookSource;

    @Column(name = "external_id", length = 120)
    private String externalId;

    @Column(name = "actor_name", length = 160)
    private String actorName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rollback_of_id")
    private Deployment rollbackOf;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    @Column(name = "rollback_reason", length = 1000)
    private String rollbackReason;

    @Column(length = 2000)
    private String notes;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /** Release 4: recorded by a simulation scenario (labelled SIMULATION, purged with the simulated data). */
    @Column(nullable = false)
    private boolean simulated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version")
    private long rowVersion;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

package com.nexus360.deployment;

import com.nexus360.cmdb.BusinessService;
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
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A CI/CD webhook source: provider (GitHub HMAC {@code X-Hub-Signature-256}, GitLab {@code X-Gitlab-Token}, or the generic
 * signed JSON format), the shared secret (encrypted with {@code SecretCipher}, shown once), the deployed service and the
 * environment used when the payload names none.
 */
@Getter
@Setter
@Entity
@Table(name = "deployment_sources")
public class DeploymentSource extends BaseEntity {

    public enum Provider { GITHUB, GITLAB, GENERIC }

    @Column(nullable = false, length = 120, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Provider provider;

    @Column(name = "secret_encrypted", nullable = false, length = 500)
    private String secretEncrypted;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id")
    private BusinessService service;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_environment", nullable = false, length = 12)
    private Deployment.Environment defaultEnvironment = Deployment.Environment.PRODUCTION;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_delivery_at")
    private Instant lastDeliveryAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;
}

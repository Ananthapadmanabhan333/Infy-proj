package com.nexus360.alert;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.ConfigurationItem;
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
 * An alert webhook source. PROMETHEUS and GRAFANA authenticate with {@code Authorization: Bearer <secret>} (or HTTP Basic with
 * the secret as password; GRAFANA alternatively with its HMAC signature header), GENERIC with an HMAC-SHA256 signature, CLOUDWATCH with the AWS SNS message signature for the configured
 * topic ARN. Secrets are stored encrypted ({@code SecretCipher}) and shown once.
 */
@Getter
@Setter
@Entity
@Table(name = "alert_sources")
public class AlertSource extends BaseEntity {

    public enum Provider { PROMETHEUS, GRAFANA, CLOUDWATCH, GENERIC }

    public enum SubscriptionStatus { NONE, PENDING, CONFIRMED, UNSUBSCRIBED }

    @Column(nullable = false, length = 120, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Provider provider;

    @Column(name = "secret_encrypted", length = 500)
    private String secretEncrypted;

    @Column(name = "sns_topic_arn", length = 300)
    private String snsTopicArn;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_status", nullable = false, length = 14)
    private SubscriptionStatus subscriptionStatus = SubscriptionStatus.NONE;

    @Column(name = "subscription_arn", length = 300)
    private String subscriptionArn;

    @Column(name = "subscription_confirmed_at")
    private Instant subscriptionConfirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_service_id")
    private BusinessService defaultService;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_ci_id")
    private ConfigurationItem defaultCi;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_severity", nullable = false, length = 10)
    private AlertSeverity defaultSeverity = AlertSeverity.WARNING;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_received_at")
    private Instant lastReceivedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;
}

package com.nexus360.integration;

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
import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "integrations")
public class Integration extends BaseEntity {

    public enum Type { WEBHOOK, SLACK, TEAMS, MONITORING_INBOUND }

    @Column(nullable = false, length = 120, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "integration_type", nullable = false, length = 30)
    private Type type;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "endpoint_url", length = 500)
    private String endpointUrl;

    /** AES-GCM encrypted signing secret (outbound webhooks). Never returned by the API. */
    @Column(name = "secret_encrypted", length = 1000)
    private String secretEncrypted;

    /** SHA-256 of the inbound token (monitoring integrations). The raw token is shown once. */
    @Column(name = "inbound_token_hash", length = 64, unique = true)
    private String inboundTokenHash;

    @Column(name = "subscribed_events", nullable = false, length = 500)
    private String subscribedEvents = "";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_service_id")
    private BusinessService defaultService;

    @Column(name = "last_delivery_at")
    private Instant lastDeliveryAt;

    @Column(name = "last_delivery_status", length = 10)
    private String lastDeliveryStatus;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    public List<String> eventList() {
        return subscribedEvents == null || subscribedEvents.isBlank() ? List.of()
                : Arrays.stream(subscribedEvents.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}

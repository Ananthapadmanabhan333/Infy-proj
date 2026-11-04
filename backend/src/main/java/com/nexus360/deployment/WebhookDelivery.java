package com.nexus360.deployment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** One accepted webhook delivery (unique per source and delivery id: replays are not processed again). */
@Getter
@Setter
@Entity
@Table(name = "deployment_webhook_deliveries")
public class WebhookDelivery {

    public enum Outcome { PROCESSED, IGNORED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "delivery_id", nullable = false, length = 200)
    private String deliveryId;

    @Column(name = "event_type", length = 60)
    private String eventType;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Outcome outcome;

    @Column(length = 500)
    private String message;

    @Column(name = "deployment_id")
    private Long deploymentId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}

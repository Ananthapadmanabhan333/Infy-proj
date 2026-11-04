package com.nexus360.integration;

import com.nexus360.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "integration_deliveries")
public class IntegrationDelivery extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "integration_id")
    private Integration integration;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_snippet", length = 500)
    private String responseSnippet;

    @Column(name = "duration_ms")
    private Integer durationMs;
}

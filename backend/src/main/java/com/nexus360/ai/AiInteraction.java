package com.nexus360.ai;

import com.nexus360.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Usage log of AI features (no prompt or response content is stored). */
@Getter
@Setter
@Entity
@Table(name = "ai_interactions")
public class AiInteraction extends CreatedEntity {

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 40)
    private String feature;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(length = 80)
    private String model;

    @Column(name = "incident_id")
    private Long incidentId;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(nullable = false)
    private boolean success;

    @Column(length = 500)
    private String error;

    /** What the interaction was about (incident number, question or goal), shown in "Recent Analyses". */
    @Column(length = 200)
    private String subject;
}

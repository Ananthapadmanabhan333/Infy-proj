package com.nexus360.incident;

import com.nexus360.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One escalation of an incident. SLA escalations reference their SLA clock; a unique index on
 * (incident_sla_id, level) guarantees each SLA level escalates at most once.
 */
@Getter
@Setter
@Entity
@Table(name = "incident_escalations")
public class IncidentEscalation extends CreatedEntity {

    public enum Source { SLA, MANUAL, WORKFLOW, AI }

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "incident_sla_id")
    private Long incidentSlaId;

    @Column(nullable = false)
    private int level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false, length = 120)
    private String actorName;
}

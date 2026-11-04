package com.nexus360.sla;

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
@Table(name = "sla_events")
public class SlaEvent extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_sla_id")
    private IncidentSla incidentSla;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Column(length = 500)
    private String details;
}

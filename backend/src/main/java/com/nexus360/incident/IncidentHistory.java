package com.nexus360.incident;

import com.nexus360.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Field-level change history shown on the incident timeline. */
@Getter
@Setter
@Entity
@Table(name = "incident_history")
public class IncidentHistory extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false, length = 120)
    private String actorName;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(length = 40)
    private String field;

    @Column(name = "old_value", length = 500)
    private String oldValue;

    @Column(name = "new_value", length = 500)
    private String newValue;

    @Column(length = 1000)
    private String message;
}

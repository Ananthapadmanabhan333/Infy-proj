package com.nexus360.major;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** One war-room timeline entry of a major incident. */
@Getter
@Setter
@Entity
@Table(name = "major_incident_timeline")
public class MajorTimelineEntry extends com.nexus360.common.CreatedEntity {

    /** NOTE, DECISION, ACTION and INVESTIGATION are written by responders; the others by the system. */
    public enum Type { NOTE, DECISION, ACTION, INVESTIGATION, STATUS, ROLE, UPDATE, SYSTEM }

    @Column(name = "major_incident_id", nullable = false)
    private Long majorIncidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private Type entryType;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false, length = 160)
    private String actorName;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;
}

package com.nexus360.change;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Explicit link between a change and an incident: the change CAUSED_BY-relation (incident caused by it), RESOLVES or RELATED. */
@Getter
@Setter
@Entity
@Table(name = "change_incidents")
public class ChangeIncident {

    public enum Relation { CAUSED_BY, RESOLVES, RELATED }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "change_id")
        private Long changeId;

        @Column(name = "incident_id")
        private Long incidentId;
    }

    @EmbeddedId
    private Key id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Relation relation;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

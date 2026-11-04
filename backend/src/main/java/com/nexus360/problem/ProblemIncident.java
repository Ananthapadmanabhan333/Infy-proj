package com.nexus360.problem;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Link of an incident to its problem (an incident belongs to at most one problem). */
@Getter
@Setter
@Entity
@Table(name = "problem_incidents")
public class ProblemIncident {

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "problem_id")
        private Long problemId;

        @Column(name = "incident_id")
        private Long incidentId;
    }

    @EmbeddedId
    private Key id;

    @Column(name = "linked_by_id")
    private Long linkedById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

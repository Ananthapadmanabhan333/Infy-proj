package com.nexus360.incident;

import com.nexus360.common.CreatedEntity;
import com.nexus360.user.User;
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
@Table(name = "incident_comments")
public class IncidentComment extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** Work notes visible only to staff. */
    @Column(nullable = false)
    private boolean internal;

    @Column(name = "system_generated", nullable = false)
    private boolean systemGenerated;
}

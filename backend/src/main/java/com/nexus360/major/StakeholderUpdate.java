package com.nexus360.major;

import com.nexus360.statuspage.StatusUpdate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** An internal stakeholder update of a major incident; {@link #statusUpdateId} is set once it is published on the status page. */
@Getter
@Setter
@Entity
@Table(name = "stakeholder_updates")
public class StakeholderUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "major_incident_id", nullable = false)
    private Long majorIncidentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusUpdate.Status status;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "status_update_id")
    private Long statusUpdateId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "posted_by_id")
    private Long postedById;

    @Column(name = "posted_by_name", nullable = false, length = 160)
    private String postedByName;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;
}

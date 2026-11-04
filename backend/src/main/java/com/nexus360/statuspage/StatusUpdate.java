package com.nexus360.statuspage;

import com.nexus360.incident.Incident;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** An incident communication on the status page; only {@code publicVisible} updates appear on the public page. */
@Getter
@Setter
@Entity
@Table(name = "status_updates")
public class StatusUpdate {

    public enum Status { INVESTIGATING, IDENTIFIED, MONITORING, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "is_public", nullable = false)
    private boolean publicVisible;

    @Column(name = "posted_by_id")
    private Long postedById;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;
}

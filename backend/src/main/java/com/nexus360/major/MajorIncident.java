package com.nexus360.major;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.incident.Incident;
import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * Major incident command record (one per major incident): the command roles, war-room timeline ({@link MajorTimelineEntry}),
 * stakeholder updates ({@link StakeholderUpdate}), impacted services and follow-up actions ({@link ActionItem}).
 */
@Getter
@Setter
@Entity
@Table(name = "major_incidents")
public class MajorIncident extends com.nexus360.common.BaseEntity {

    /** {@code ACTIVE → MONITORING → RESOLVED → CLOSED}; MONITORING and RESOLVED may return to ACTIVE. */
    public enum Status {
        ACTIVE, MONITORING, RESOLVED, CLOSED;

        static final Map<Status, Set<Status>> NEXT = Map.of(
                ACTIVE, EnumSet.of(MONITORING, RESOLVED),
                MONITORING, EnumSet.of(ACTIVE, RESOLVED),
                RESOLVED, EnumSet.of(ACTIVE, CLOSED),
                CLOSED, EnumSet.noneOf(Status.class));

        public Set<Status> next() {
            return NEXT.get(this);
        }
    }

    @Column(nullable = false, length = 20, unique = true, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commander_id")
    private User commander;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tech_lead_id")
    private User techLead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comms_lead_id")
    private User commsLead;

    @Column(length = 2000)
    private String summary;

    @Column(name = "impact_summary", columnDefinition = "text")
    private String impactSummary;

    @Column(name = "investigation_notes", columnDefinition = "text")
    private String investigationNotes;

    @Column(name = "resolution_summary", columnDefinition = "text")
    private String resolutionSummary;

    @Column(name = "bridge_url", length = 500)
    private String bridgeUrl;

    @Column(name = "declared_at", nullable = false)
    private Instant declaredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declared_by_id")
    private User declaredBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "major_incident_services", joinColumns = @JoinColumn(name = "major_incident_id"),
            inverseJoinColumns = @JoinColumn(name = "service_id"))
    private Set<BusinessService> services = new LinkedHashSet<>();

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Version
    private long version;
}

package com.nexus360.alert;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.ConfigurationItem;
import com.nexus360.common.BaseEntity;
import com.nexus360.team.Team;
import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Routes matching alerts: the first enabled rule by {@link #sortOrder} (then id) wins. It sets the {@link #team}, fills the
 * {@link #service} and {@link #ci} when the alert has none, and with {@link #createIncident} converts alerts of at least
 * {@link #incidentMinSeverity} into incidents through the incident intake pipeline.
 */
@Getter
@Setter
@Entity
@Table(name = "alert_routing_rules")
public class AlertRoutingRule extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "match_criteria", nullable = false, columnDefinition = "jsonb")
    private MatchCriteria matchCriteria = MatchCriteria.ANY;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private BusinessService service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ci_id")
    private ConfigurationItem ci;

    @Column(name = "create_incident", nullable = false)
    private boolean createIncident;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_min_severity", nullable = false, length = 10)
    private AlertSeverity incidentMinSeverity = AlertSeverity.MAJOR;

    @Column(name = "match_count", nullable = false)
    private long matchCount;

    @Column(name = "last_matched_at")
    private Instant lastMatchedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Version
    private long version;
}

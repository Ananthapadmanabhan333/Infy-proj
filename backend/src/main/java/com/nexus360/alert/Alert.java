package com.nexus360.alert;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.ConfigurationItem;
import com.nexus360.incident.Incident;
import com.nexus360.integration.Integration;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One alert: deduplicated by {@link #scopeKey} + {@link #fingerprint} while active (OPEN, ACKNOWLEDGED, SUPPRESSED), with
 * {@link #occurrenceCount} counting every notification received. {@link #firing} is the last state reported by the source;
 * {@link #status} is the operational state. Alerts of one notification group (e.g. an Alertmanager group) share {@link #groupKey}.
 * Escalation state ({@link #escalationLevel}, {@link #nextEscalationAt}) is managed by {@link AlertEscalationService}.
 */
@Getter
@Setter
@Entity
@Table(name = "alerts")
public class Alert extends com.nexus360.common.BaseEntity {

    public enum Status {
        OPEN, ACKNOWLEDGED, RESOLVED, SUPPRESSED;

        public static final Set<Status> ACTIVE = EnumSet.of(OPEN, ACKNOWLEDGED, SUPPRESSED);

        public boolean active() {
            return ACTIVE.contains(this);
        }
    }

    /** {@code OBSERVABILITY} (Release 4): raised by an SA Observability anomaly detector. */
    public enum SourceKind { PROMETHEUS, GRAFANA, CLOUDWATCH, GENERIC, INTEGRATION, MANUAL, OBSERVABILITY }

    @Column(nullable = false, length = 20, unique = true, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 16)
    private SourceKind sourceKind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    private AlertSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "integration_id")
    private Integration integration;

    @Column(name = "scope_key", nullable = false, length = 40)
    private String scopeKey;

    @Column(nullable = false, length = 128)
    private String fingerprint;

    @Column(name = "group_key", length = 300)
    private String groupKey;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private boolean firing = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> labels = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> annotations = new LinkedHashMap<>();

    @Column(name = "external_url", length = 500)
    private String externalUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private BusinessService service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ci_id")
    private ConfigurationItem ci;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "routing_rule_id")
    private AlertRoutingRule routingRule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suppression_rule_id")
    private AlertSuppressionRule suppressionRule;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount = 1;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acknowledged_by_id")
    private User acknowledgedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_id")
    private User resolvedBy;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "suppressed_at")
    private Instant suppressedAt;

    @Column(name = "suppressed_until")
    private Instant suppressedUntil;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suppressed_by_id")
    private User suppressedBy;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "last_escalated_at")
    private Instant lastEscalatedAt;

    @Column(name = "next_escalation_at")
    private Instant nextEscalationAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /** Release 4: generated by a simulation scenario (purged with the simulated data). */
    @Column(nullable = false)
    private boolean simulated;

    @Version
    private long version;

    /** The alert name (Prometheus/Grafana {@code alertname}, CloudWatch {@code alarmName}), else the title. */
    public String alertName() {
        String n = labels == null ? null : labels.getOrDefault("alertname", labels.get("alarmName"));
        return n == null || n.isBlank() ? title : n;
    }
}

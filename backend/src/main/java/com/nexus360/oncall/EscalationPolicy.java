package com.nexus360.oncall;

import com.nexus360.alert.AlertSeverity;
import com.nexus360.team.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * On-call escalation policy: ordered levels, each paging a target after a delay (minutes after the previous level; level 1 after
 * the alert became active). Used by {@link OnCallEscalation} for alerts of teams whose schedule references the policy.
 */
@Getter
@Setter
@Entity
@Table(name = "oncall_escalation_policies")
public class EscalationPolicy extends com.nexus360.common.BaseEntity {

    /** Who a level pages: the schedule's primary or secondary on call, the team lead and department manager, or a named user. */
    public enum Target { PRIMARY, SECONDARY, MANAGER, USER }

    public record Level(int delayMinutes, Target target, Long userId) {}

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(name = "min_severity", nullable = false, length = 10)
    private AlertSeverity minSeverity = AlertSeverity.MAJOR;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Level> levels = new ArrayList<>();

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Column(name = "created_by_id")
    private Long createdById;

    @Version
    private long version;
}

package com.nexus360.change;

import com.nexus360.approval.Approval;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.common.BaseEntity;
import com.nexus360.incident.Category;
import com.nexus360.incident.Level;
import com.nexus360.incident.Priority;
import com.nexus360.problem.Problem;
import com.nexus360.team.Team;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Change record (CHG-####): STANDARD (template, pre-approved), NORMAL (CAB) or EMERGENCY (expedited, with review). */
@Getter
@Setter
@Entity
@Table(name = "changes")
public class Change extends BaseEntity {

    public enum Type { STANDARD, NORMAL, EMERGENCY }

    public enum Risk { LOW, MEDIUM, HIGH }

    @Column(nullable = false, length = 20, unique = true, updatable = false)
    private String number;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 10)
    private Type type = Type.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChangeStatus status = ChangeStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Risk risk = Risk.LOW;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_answers", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> riskAnswers = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_factors", nullable = false, columnDefinition = "jsonb")
    private List<ChangeRisk.Factor> riskFactors = new ArrayList<>();

    @Column(name = "risk_assessed_at")
    private Instant riskAssessedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Level impact = Level.LOW;

    @Column(name = "affected_users", nullable = false)
    private int affectedUsers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "affected_cis", nullable = false, columnDefinition = "jsonb")
    private List<String> affectedCis = new ArrayList<>();

    @Column(columnDefinition = "text")
    private String justification;

    @Column(name = "implementation_plan", columnDefinition = "text")
    private String implementationPlan;

    @Column(name = "rollback_plan", columnDefinition = "text")
    private String rollbackPlan;

    @Column(name = "test_plan", columnDefinition = "text")
    private String testPlan;

    @Column(name = "planned_start")
    private Instant plannedStart;

    @Column(name = "planned_end")
    private Instant plannedEnd;

    @Column(name = "actual_start")
    private Instant actualStart;

    @Column(name = "actual_end")
    private Instant actualEnd;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id")
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private ChangeTemplate template;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_id")
    private Approval approval;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    @Column(name = "outcome_notes", columnDefinition = "text")
    private String outcomeNotes;

    @Column(name = "pir_required", nullable = false)
    private boolean pirRequired;

    @Column(name = "pir_notes", columnDefinition = "text")
    private String pirNotes;

    @Column(name = "pir_completed_at")
    private Instant pirCompletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pir_by_id")
    private User pirBy;

    @Column(name = "conflict_override_reason", length = 1000)
    private String conflictOverrideReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToMany
    @JoinTable(name = "change_services", joinColumns = @JoinColumn(name = "change_id"), inverseJoinColumns = @JoinColumn(name = "service_id"))
    private Set<BusinessService> services = new LinkedHashSet<>();

    @Version
    private long version;
}

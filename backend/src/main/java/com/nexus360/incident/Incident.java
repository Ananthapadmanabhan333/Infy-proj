package com.nexus360.incident;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.common.BaseEntity;
import com.nexus360.knowledge.KnowledgeArticle;
import com.nexus360.user.Department;
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
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "incidents")
public class Incident extends BaseEntity {

    @Column(nullable = false, length = 20, unique = true, updatable = false)
    private String number;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification_source", nullable = false, length = 20)
    private ClassificationSource classificationSource = ClassificationSource.MANUAL;

    @Column(name = "classification_confidence", precision = 4, scale = 3)
    private BigDecimal classificationConfidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Level impact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Level urgency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status = IncidentStatus.NEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private BusinessService service;

    /** Assignment group. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    /** Team within the assignment group (Release 3); the assignee is normally one of its members. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private com.nexus360.team.Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id")
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_id")
    private User resolvedBy;

    /** Parent incident when this report was linked as a duplicate / child. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Incident parent;

    @Column(name = "is_major", nullable = false)
    private boolean major;

    @Column(name = "affected_users", nullable = false)
    private int affectedUsers = 1;

    @Column(name = "duplicate_score", precision = 4, scale = 3)
    private BigDecimal duplicateScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentSource source = IncidentSource.PORTAL;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_code", length = 30)
    private ResolutionCode resolutionCode;

    @Column(name = "resolution_notes", columnDefinition = "text")
    private String resolutionNotes;

    @Column(name = "root_cause", columnDefinition = "text")
    private String rootCause;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolution_article_id")
    private KnowledgeArticle resolutionArticle;

    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /** Release 4: raised from a simulated alert (simulation mode); labelled SIMULATION and purged with the simulated data. */
    @Column(nullable = false)
    private boolean simulated;

    @Version
    private long version;
}

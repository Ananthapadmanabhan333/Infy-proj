package com.nexus360.problem;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.common.BaseEntity;
import com.nexus360.incident.Category;
import com.nexus360.incident.Priority;
import com.nexus360.knowledge.KnowledgeArticle;
import com.nexus360.team.Team;
import com.nexus360.user.Department;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Problem record (PRB-####): the underlying cause of one or more incidents (docs/RELEASE3_SPEC.md §5 BE-2). */
@Getter
@Setter
@Entity
@Table(name = "problems")
public class Problem extends BaseEntity {

    @Column(nullable = false, length = 20, unique = true, updatable = false)
    private String number;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private ProblemStatus status = ProblemStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "root_cause", columnDefinition = "text")
    private String rootCause;

    @Column(columnDefinition = "text")
    private String workaround;

    @Column(name = "permanent_fix", columnDefinition = "text")
    private String permanentFix;

    @Column(name = "known_error", nullable = false)
    private boolean knownError;

    @Column(name = "known_error_at")
    private Instant knownErrorAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kb_article_id")
    private KnowledgeArticle kbArticle;

    /** Affected configuration items by name (the CMDB module may link them to CI records). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "affected_cis", nullable = false, columnDefinition = "jsonb")
    private List<String> affectedCis = new ArrayList<>();

    @Column(name = "closure_note", length = 1000)
    private String closureNote;

    @Column(name = "root_cause_at")
    private Instant rootCauseAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToMany
    @JoinTable(name = "problem_services", joinColumns = @JoinColumn(name = "problem_id"), inverseJoinColumns = @JoinColumn(name = "service_id"))
    private Set<BusinessService> services = new LinkedHashSet<>();

    @Version
    private long version;
}

package com.nexus360.runbook;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.common.BaseEntity;
import com.nexus360.incident.Category;
import com.nexus360.user.User;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "runbooks")
public class Runbook extends BaseEntity {

    /** {@code requiresApproval}: a sensitive step; completing it needs an incident manager's approval. */
    public record Step(int order, String title, String instruction, boolean requiresApproval) {

        public Step(int order, String title, String instruction) {
            this(order, title, instruction, false);
        }
    }

    @Column(nullable = false, length = 200, unique = true)
    private String title;

    @Column(length = 1000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private BusinessService service;

    /** Incident categories the runbook applies to. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "runbook_categories", joinColumns = @JoinColumn(name = "runbook_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20, nullable = false)
    private Set<Category> categories = new HashSet<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> prerequisites = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Step> steps = new ArrayList<>();

    /** Sensitive procedures (restarts, failovers, data fixes) need manager approval before they can be executed. */
    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Version
    private long version;
}

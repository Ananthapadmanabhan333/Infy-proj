package com.nexus360.change;

import com.nexus360.common.BaseEntity;
import com.nexus360.incident.Category;
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
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Pre-approved standard change template: low-risk, repeatable work that skips the CAB. */
@Getter
@Setter
@Entity
@Table(name = "change_templates")
public class ChangeTemplate extends BaseEntity {

    @Column(nullable = false, length = 40, unique = true)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Category category;

    @Column(name = "implementation_plan", nullable = false, columnDefinition = "text")
    private String implementationPlan;

    @Column(name = "rollback_plan", nullable = false, columnDefinition = "text")
    private String rollbackPlan;

    @Column(name = "test_plan", columnDefinition = "text")
    private String testPlan;

    @Column(name = "default_duration_minutes", nullable = false)
    private int defaultDurationMinutes = 60;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "service_ids", nullable = false, columnDefinition = "jsonb")
    private List<Long> serviceIds = new ArrayList<>();

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Version
    private long version;
}

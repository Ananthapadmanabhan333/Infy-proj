package com.nexus360.cmdb;

import com.nexus360.common.BaseEntity;
import com.nexus360.incident.Category;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;

/** A business service (Payroll, HR Portal, VPN...) owned and supported by a department. */
@Getter
@Setter
@Entity
@Table(name = "business_services")
public class BusinessService extends BaseEntity {

    @Column(nullable = false, length = 40, unique = true)
    private String code;

    @Column(nullable = false, length = 120, unique = true)
    private String name;

    @Column(length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    /** Team that receives this service's incidents (Release 3); null = the department's default team. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_team_id")
    private com.nexus360.team.Team defaultTeam;

    /** 1 = business critical, 3 = low criticality. */
    @Column(nullable = false)
    private int tier = 2;

    /** Comma-separated terms used by the rule-based classifier. */
    @Column(length = 500)
    private String keywords;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_category", length = 20)
    private Category defaultCategory;

    @Column(nullable = false)
    private boolean maintenance;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /** Services this service needs in order to work (no cycles, never itself). */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "service_dependencies", joinColumns = @JoinColumn(name = "service_id"),
            inverseJoinColumns = @JoinColumn(name = "depends_on_id"))
    private Set<BusinessService> dependsOn = new HashSet<>();

    public List<String> keywordList() {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        return Arrays.stream(keywords.split(","))
                .map(k -> k.trim().toLowerCase(Locale.ROOT))
                .filter(k -> !k.isEmpty())
                .toList();
    }
}

package com.nexus360.team;

import com.nexus360.common.BaseEntity;
import com.nexus360.user.Department;
import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * A support team inside a department (e.g. "Payroll &amp; HR Apps" in Enterprise Applications). Incidents, service
 * requests and the Release 3 modules (problems, changes, alerts, on-call) are assigned to a team and then to one of its
 * members. One active team per department can be the department's default team.
 */
@Getter
@Setter
@Entity
@Table(name = "teams")
public class Team extends BaseEntity {

    @Column(nullable = false, length = 40, unique = true, updatable = false)
    private String code;

    @Column(nullable = false, length = 120, unique = true)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private User lead;

    @Column(length = 160)
    private String email;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "department_default", nullable = false)
    private boolean departmentDefault;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Version
    private long version;
}

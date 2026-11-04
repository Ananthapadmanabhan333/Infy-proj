package com.nexus360.servicedesk;

import com.nexus360.common.BaseEntity;
import com.nexus360.user.Department;
import com.nexus360.user.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "catalog_items")
public class CatalogItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private ServiceCategory category;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 1000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fulfillment_department_id")
    private Department fulfillmentDepartment;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_role", length = 20)
    private Role approverRole;

    @Column(name = "fulfillment_hours", nullable = false)
    private int fulfillmentHours = 24;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_fields", nullable = false, columnDefinition = "jsonb")
    private List<FormField> formFields = new ArrayList<>();

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /** Form definition the item uses (Release 3 form builder); {@code formFields} mirrors its current version. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_definition_id")
    private com.nexus360.form.FormDefinition formDefinition;

    /** Roles allowed to submit this request type (empty = everyone). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_roles", nullable = false, columnDefinition = "jsonb")
    private List<Role> allowedRoles = new ArrayList<>();

    /** Departments whose members may submit this request type (empty = every department). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_department_ids", nullable = false, columnDefinition = "jsonb")
    private List<Long> allowedDepartmentIds = new ArrayList<>();
}

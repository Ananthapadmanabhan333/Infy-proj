package com.nexus360.servicedesk;

import com.nexus360.common.BaseEntity;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "service_requests")
public class ServiceRequest extends BaseEntity {

    public enum Status { PENDING_APPROVAL, APPROVED, REJECTED, IN_FULFILLMENT, FULFILLED, CANCELLED }

    @Column(nullable = false, length = 20, unique = true, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "catalog_item_id")
    private CatalogItem catalogItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id")
    private User requester;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String details;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> formData = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private com.nexus360.incident.Priority priority = com.nexus360.incident.Priority.MEDIUM;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    /** Fulfilment team (Release 3): the fulfilment department's default team unless assigned otherwise. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private com.nexus360.team.Team team;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "fulfillment_notes", columnDefinition = "text")
    private String fulfillmentNotes;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /** Form definition and version the request was submitted with (Release 3). */
    @Column(name = "form_definition_id")
    private Long formDefinitionId;

    @Column(name = "form_version")
    private Integer formVersion;

    @Version
    private long version;
}

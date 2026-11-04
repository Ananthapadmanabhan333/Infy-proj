package com.nexus360.approval;

import com.nexus360.common.BaseEntity;
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

/** A reusable approval flow: ordered stage definitions (see {@link ApprovalModel}). */
@Getter
@Setter
@Entity
@Table(name = "approval_flows")
public class ApprovalFlow extends BaseEntity {

    @Column(nullable = false, length = 40, unique = true)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", length = 20)
    private Approval.Type subjectType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ApprovalModel.StageDef> stages = new ArrayList<>();

    @Column(nullable = false)
    private boolean active = true;

    /** Flows the platform relies on (CHANGE_NORMAL, CHANGE_EMERGENCY): editable, never deleted. */
    @Column(name = "system_flow", nullable = false)
    private boolean systemFlow;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Version
    private long version;
}

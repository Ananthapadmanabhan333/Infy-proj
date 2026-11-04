package com.nexus360.workflow;

import com.nexus360.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable snapshot of a workflow definition; executions record the version they ran. */
@Getter
@Setter
@Entity
@Table(name = "workflow_versions")
public class WorkflowVersion extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id")
    private Workflow workflow;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private WorkflowModel.Definition definition;

    @Column(name = "change_note", length = 500)
    private String changeNote;

    @Column(name = "created_by_id")
    private Long createdById;
}

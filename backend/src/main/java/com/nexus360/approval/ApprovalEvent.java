package com.nexus360.approval;

import com.nexus360.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * History of an approval. Event types: REQUESTED, STAGE_STARTED, STAGE_SKIPPED, TASK_APPROVED, TASK_REJECTED, DELEGATED,
 * STAGE_APPROVED, STAGE_REJECTED, ESCALATED, AUTO_REJECTED, APPROVED, REJECTED, CANCELLED.
 */
@Getter
@Setter
@Entity
@Table(name = "approval_events")
public class ApprovalEvent extends CreatedEntity {

    @Column(name = "approval_id", nullable = false)
    private Long approvalId;

    @Column(name = "stage_order")
    private Integer stageOrder;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false, length = 160)
    private String actorName;

    @Column(length = 1000)
    private String comment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;
}

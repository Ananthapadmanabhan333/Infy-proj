package com.nexus360.audit;

import com.nexus360.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only record; the database rejects updates and deletes (see trigger in V1) and Hibernate never issues them. */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog extends CreatedEntity {

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_email", length = 160)
    private String actorEmail;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", length = 40)
    private String entityType;

    @Column(name = "entity_id", length = 40)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(nullable = false, length = 10)
    private String outcome = "SUCCESS";
}

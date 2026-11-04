package com.nexus360.servicedesk;

import com.nexus360.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Append-only change log of a service request (creation, approval, assignment, status, comments). */
@Getter
@Setter
@Entity
@Table(name = "service_request_history")
public class ServiceRequestHistory extends CreatedEntity {

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false, length = 120)
    private String actorName;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "from_value", length = 200)
    private String fromValue;

    @Column(name = "to_value", length = 200)
    private String toValue;

    @Column(length = 1000)
    private String note;
}

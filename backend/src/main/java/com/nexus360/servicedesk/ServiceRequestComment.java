package com.nexus360.servicedesk;

import com.nexus360.common.CreatedEntity;
import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Public reply or internal (staff-only) note on a service request. */
@Getter
@Setter
@Entity
@Table(name = "service_request_comments")
public class ServiceRequestComment extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id")
    private ServiceRequest request;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(nullable = false)
    private boolean internal;

    @Column(name = "system_generated", nullable = false)
    private boolean systemGenerated;
}

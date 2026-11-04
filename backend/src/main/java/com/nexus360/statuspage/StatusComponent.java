package com.nexus360.statuspage;

import com.nexus360.cmdb.BusinessService;
import com.nexus360.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A business service shown on the status page; its status and uptime are derived from the service's incidents. */
@Getter
@Setter
@Entity
@Table(name = "status_components")
public class StatusComponent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", unique = true)
    private BusinessService service;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "group_name", length = 80)
    private String groupName;

    @Column(length = 500)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Shown on the public page (internal users always see every component). */
    @Column(name = "is_public", nullable = false)
    private boolean publicVisible = true;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;
}

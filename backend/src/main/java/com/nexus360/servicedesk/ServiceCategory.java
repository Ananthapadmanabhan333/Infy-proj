package com.nexus360.servicedesk;

import com.nexus360.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "service_categories")
public class ServiceCategory extends CreatedEntity {

    @Column(nullable = false, length = 40, unique = true)
    private String code;

    @Column(nullable = false, length = 120, unique = true)
    private String name;

    @Column(length = 500)
    private String description;

    /** lucide-react icon name rendered by the frontend. */
    @Column(length = 40)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;
}

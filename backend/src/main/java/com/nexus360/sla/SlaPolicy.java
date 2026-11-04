package com.nexus360.sla;

import com.nexus360.common.BaseEntity;
import com.nexus360.incident.Priority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sla_policies")
public class SlaPolicy extends BaseEntity {

    @Column(nullable = false, length = 120, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority;

    @Column(name = "response_minutes", nullable = false)
    private int responseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    @Column(name = "business_hours_only", nullable = false)
    private boolean businessHoursOnly;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_id")
    private BusinessHoursCalendar calendar;

    @Column(name = "at_risk_percent", nullable = false)
    private int atRiskPercent = 75;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 500)
    private String description;
}

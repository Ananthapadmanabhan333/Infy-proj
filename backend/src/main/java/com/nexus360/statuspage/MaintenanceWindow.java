package com.nexus360.statuspage;

import com.nexus360.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * Planned maintenance on one or more components. The stored status is what editors set; {@link #effectiveStatus} also
 * follows the clock (a scheduled window is in progress between start and end, and completed afterwards), and services of
 * an in-progress window are shown as MAINTENANCE.
 */
@Getter
@Setter
@Entity
@Table(name = "maintenance_windows")
public class MaintenanceWindow extends BaseEntity {

    public enum Status { SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED }

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String description;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.SCHEDULED;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Version
    private long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "maintenance_window_components", joinColumns = @JoinColumn(name = "window_id"),
            inverseJoinColumns = @JoinColumn(name = "component_id"))
    private Set<StatusComponent> components = new LinkedHashSet<>();

    public Status effectiveStatus(Instant now) {
        if (status == Status.CANCELLED || status == Status.COMPLETED) {
            return status;
        }
        if (!now.isBefore(endsAt)) {
            return Status.COMPLETED;
        }
        if (status == Status.IN_PROGRESS || !now.isBefore(startsAt)) {
            return Status.IN_PROGRESS;
        }
        return Status.SCHEDULED;
    }
}

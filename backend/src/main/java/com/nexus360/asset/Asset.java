package com.nexus360.asset;

import com.nexus360.cmdb.ConfigurationItem;
import com.nexus360.common.BaseEntity;
import com.nexus360.user.Department;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * A hardware or software asset. Hardware has at most one holder ({@link #assignedTo}); a software licence has
 * {@link #licenseSeats} seats assigned to users (open rows in {@code asset_assignments}). Lifecycle:
 * {@code IN_STOCK → IN_USE → IN_REPAIR → RETIRED → DISPOSED} with the transitions of {@link Status#next()}.
 */
@Getter
@Setter
@Entity
@Table(name = "assets")
public class Asset extends BaseEntity {

    public enum Type {
        LAPTOP, DESKTOP, SERVER, NETWORK, MOBILE, MONITOR, PERIPHERAL, SOFTWARE_LICENSE, OTHER;

        public boolean isLicense() {
            return this == SOFTWARE_LICENSE;
        }

        /** End-user devices need a holder to be IN_USE; servers, network gear and other equipment do not. */
        public boolean personal() {
            return this == LAPTOP || this == DESKTOP || this == MOBILE || this == MONITOR || this == PERIPHERAL;
        }

        public String category() {
            return isLicense() ? "SOFTWARE" : "HARDWARE";
        }
    }

    public enum Status {
        IN_STOCK, IN_USE, IN_REPAIR, RETIRED, DISPOSED;

        /** Allowed lifecycle moves. */
        public Set<Status> next() {
            return switch (this) {
                case IN_STOCK -> EnumSet.of(IN_USE, IN_REPAIR, RETIRED);
                case IN_USE -> EnumSet.of(IN_STOCK, IN_REPAIR, RETIRED);
                case IN_REPAIR -> EnumSet.of(IN_STOCK, IN_USE, RETIRED);
                case RETIRED -> EnumSet.of(IN_STOCK, DISPOSED);
                case DISPOSED -> EnumSet.noneOf(Status.class);
            };
        }

        /** Statuses in which the asset can be (re)assigned. */
        public boolean assignable() {
            return this == IN_STOCK || this == IN_USE;
        }

        public boolean active() {
            return this != RETIRED && this != DISPOSED;
        }
    }

    @Column(name = "asset_tag", nullable = false, length = 40, unique = true, updatable = false)
    private String assetTag;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 20)
    private Type assetType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.IN_STOCK;

    @Column(length = 80)
    private String manufacturer;

    @Column(length = 120)
    private String model;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(length = 160)
    private String location;

    @Column(length = 120)
    private String vendor;

    @Column(name = "purchase_order", length = 60)
    private String purchaseOrder;

    @Column(name = "purchase_cost", precision = 12, scale = 2)
    private BigDecimal purchaseCost;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "warranty_expires")
    private LocalDate warrantyExpires;

    @Column(name = "license_seats")
    private Integer licenseSeats;

    @Column(name = "license_expires")
    private LocalDate licenseExpires;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ci_id")
    private ConfigurationItem ci;

    @Column(length = 2000)
    private String notes;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "disposed_at")
    private Instant disposedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Version
    private long version;
}

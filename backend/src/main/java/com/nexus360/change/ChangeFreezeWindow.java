package com.nexus360.change;

import com.nexus360.common.BaseEntity;
import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Change freeze (e.g. month-end payroll close): no NORMAL or STANDARD change may be scheduled into it. */
@Getter
@Setter
@Entity
@Table(name = "change_freeze_windows")
public class ChangeFreezeWindow extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 1000)
    private String reason;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /** Business services the freeze applies to; empty = every service. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "service_ids", nullable = false, columnDefinition = "jsonb")
    private List<Long> serviceIds = new ArrayList<>();

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;
}

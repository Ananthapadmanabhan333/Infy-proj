package com.nexus360.alert;

import com.nexus360.common.BaseEntity;
import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Suppresses matching alerts while its time window is active: optional absolute {@link #startsAt}/{@link #endsAt} plus an optional
 * daily window {@link #dailyStart}–{@link #dailyEnd} (organisation time zone; may cross midnight) on {@link #daysOfWeek}
 * (1 = Monday; empty = every day). Suppressed alerts are stored and counted but neither routed, notified, escalated nor converted.
 */
@Getter
@Setter
@Entity
@Table(name = "alert_suppression_rules")
public class AlertSuppressionRule extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "match_criteria", nullable = false, columnDefinition = "jsonb")
    private MatchCriteria matchCriteria = MatchCriteria.ANY;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "daily_start")
    private LocalTime dailyStart;

    @Column(name = "daily_end")
    private LocalTime dailyEnd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "days_of_week", nullable = false, columnDefinition = "jsonb")
    private List<Integer> daysOfWeek = new ArrayList<>();

    @Column(name = "match_count", nullable = false)
    private long matchCount;

    @Column(name = "last_matched_at")
    private Instant lastMatchedAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Version
    private long version;

    /** Whether the rule's time window is open at {@code now} (ignores {@link #enabled}). */
    public boolean windowActive(Instant now, ZoneId zone) {
        if (startsAt != null && now.isBefore(startsAt)) {
            return false;
        }
        if (endsAt != null && !now.isBefore(endsAt)) {
            return false;
        }
        ZonedDateTime local = now.atZone(zone);
        if (dailyStart != null && dailyEnd != null) {
            LocalTime t = local.toLocalTime();
            boolean overnight = dailyEnd.isBefore(dailyStart) || dailyEnd.equals(dailyStart);
            boolean inside = overnight ? !t.isBefore(dailyStart) || t.isBefore(dailyEnd) : !t.isBefore(dailyStart) && t.isBefore(dailyEnd);
            if (!inside) {
                return false;
            }
            if (daysOfWeek != null && !daysOfWeek.isEmpty()) {
                // After midnight an overnight window belongs to the day it started.
                DayOfWeek day = overnight && t.isBefore(dailyEnd) ? local.getDayOfWeek().minus(1) : local.getDayOfWeek();
                return daysOfWeek.contains(day.getValue());
            }
            return true;
        }
        return daysOfWeek == null || daysOfWeek.isEmpty() || daysOfWeek.contains(local.getDayOfWeek().getValue());
    }

    /** Whether the window can never open again (absolute end in the past). */
    public boolean expired(Instant now) {
        return endsAt != null && !now.isBefore(endsAt);
    }
}

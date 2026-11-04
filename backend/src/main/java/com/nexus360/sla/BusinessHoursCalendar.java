package com.nexus360.sla;

import com.nexus360.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "business_hours_calendars")
public class BusinessHoursCalendar extends BaseEntity {

    @Column(nullable = false, length = 80, unique = true)
    private String name;

    @Column(nullable = false, length = 60)
    private String timezone;

    /** Comma-separated {@link DayOfWeek} names. */
    @Column(name = "work_days", nullable = false, length = 80)
    private String workDays;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "is_default", nullable = false)
    private boolean defaultCalendar;

    @OneToMany(mappedBy = "calendar", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("holidayDate asc")
    private List<CalendarHoliday> holidays = new ArrayList<>();

    public Set<DayOfWeek> workDaySet() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        Arrays.stream(workDays.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(DayOfWeek::valueOf).forEach(days::add);
        return days;
    }

    public void setWorkDaySet(Set<DayOfWeek> days) {
        this.workDays = days.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
    }

    public BusinessCalendarSpec toSpec() {
        return new BusinessCalendarSpec(ZoneId.of(timezone), workDaySet(), startTime, endTime,
                holidays.stream().map(CalendarHoliday::getHolidayDate).collect(Collectors.toSet()));
    }
}

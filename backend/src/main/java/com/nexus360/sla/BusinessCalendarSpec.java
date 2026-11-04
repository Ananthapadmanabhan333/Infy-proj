package com.nexus360.sla;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/** Immutable working-time definition used by {@link BusinessTimeCalculator}. */
public record BusinessCalendarSpec(ZoneId zone, Set<DayOfWeek> workDays, LocalTime start, LocalTime end, Set<LocalDate> holidays) {

    public BusinessCalendarSpec {
        if (workDays == null || workDays.isEmpty()) {
            throw new IllegalArgumentException("A business calendar needs at least one working day");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Business day end must be after its start");
        }
        workDays = Set.copyOf(workDays);
        holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
    }

    public boolean isWorkingDay(LocalDate date) {
        return workDays.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }
}

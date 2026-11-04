package com.nexus360.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessTimeCalculatorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BusinessCalendarSpec WEEKDAYS = new BusinessCalendarSpec(IST,
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalTime.of(9, 0), LocalTime.of(18, 0), Set.of());

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, IST).toInstant();
    }

    @Test
    void twentyFourSevenIsPlainDuration() {
        Instant start = at(2026, 11, 6, 17, 0);
        assertThat(BusinessTimeCalculator.addBusinessSeconds(start, 4 * 3600, null)).isEqualTo(start.plusSeconds(4 * 3600));
        assertThat(BusinessTimeCalculator.businessSecondsBetween(start, start.plusSeconds(600), null)).isEqualTo(600);
    }

    @Test
    void deadlineSkipsNightsAndWeekends() {
        // Friday 16:00 + 4 business hours = Monday 11:00 (2 h on Friday, 2 h on Monday).
        Instant friday = at(2026, 11, 6, 16, 0);
        assertThat(BusinessTimeCalculator.addBusinessSeconds(friday, 4 * 3600, WEEKDAYS)).isEqualTo(at(2026, 11, 9, 11, 0));
    }

    @Test
    void startOutsideHoursBeginsAtNextOpening() {
        Instant beforeOpening = at(2026, 11, 4, 7, 30);
        assertThat(BusinessTimeCalculator.addBusinessSeconds(beforeOpening, 3600, WEEKDAYS)).isEqualTo(at(2026, 11, 4, 10, 0));
        Instant saturday = at(2026, 11, 7, 12, 0);
        assertThat(BusinessTimeCalculator.addBusinessSeconds(saturday, 1800, WEEKDAYS)).isEqualTo(at(2026, 11, 9, 9, 30));
    }

    @Test
    void holidaysAreSkipped() {
        BusinessCalendarSpec withHoliday = new BusinessCalendarSpec(IST, EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                LocalTime.of(9, 0), LocalTime.of(18, 0), Set.of(LocalDate.of(2026, 11, 9)));
        Instant friday = at(2026, 11, 6, 17, 0);
        // 1 h Friday, Monday is a holiday, 1 h Tuesday.
        assertThat(BusinessTimeCalculator.addBusinessSeconds(friday, 2 * 3600, withHoliday)).isEqualTo(at(2026, 11, 10, 10, 0));
    }

    @Test
    void elapsedBusinessTimeIsInverseOfAdd() {
        Instant start = at(2026, 11, 5, 15, 20);
        long seconds = 13 * 3600 + 17 * 60;
        Instant due = BusinessTimeCalculator.addBusinessSeconds(start, seconds, WEEKDAYS);
        assertThat(BusinessTimeCalculator.businessSecondsBetween(start, due, WEEKDAYS)).isEqualTo(seconds);
        assertThat(BusinessTimeCalculator.businessSecondsBetween(due, start, WEEKDAYS)).isZero();
    }
}

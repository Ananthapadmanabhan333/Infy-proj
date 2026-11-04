package com.nexus360.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Time windows of suppression rules: absolute start/end, daily windows (also across midnight) and days of the week. */
class SuppressionWindowTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void absoluteWindow() {
        AlertSuppressionRule r = new AlertSuppressionRule();
        r.setStartsAt(Instant.parse("2026-11-04T10:00:00Z"));
        r.setEndsAt(Instant.parse("2026-11-04T12:00:00Z"));
        assertThat(r.windowActive(Instant.parse("2026-11-04T09:59:59Z"), IST)).isFalse();
        assertThat(r.windowActive(Instant.parse("2026-11-04T10:00:00Z"), IST)).isTrue();
        assertThat(r.windowActive(Instant.parse("2026-11-04T12:00:00Z"), IST)).isFalse();
        assertThat(r.expired(Instant.parse("2026-11-04T12:00:00Z"))).isTrue();
        assertThat(new AlertSuppressionRule().windowActive(Instant.now(), IST)).isTrue(); // no window = always
    }

    @Test
    void dailyWindowInTheOrganisationTimeZone() {
        AlertSuppressionRule r = new AlertSuppressionRule();
        r.setDailyStart(LocalTime.of(1, 0));
        r.setDailyEnd(LocalTime.of(3, 0));
        // 01:30 IST = 20:00 UTC the day before.
        assertThat(r.windowActive(Instant.parse("2026-11-03T20:00:00Z"), IST)).isTrue();
        assertThat(r.windowActive(Instant.parse("2026-11-03T21:31:00Z"), IST)).isFalse(); // 03:01 IST
        assertThat(r.windowActive(Instant.parse("2026-11-04T01:30:00Z"), IST)).isFalse(); // 07:00 IST
    }

    @Test
    void overnightWindowsBelongToTheDayTheyStart() {
        AlertSuppressionRule r = new AlertSuppressionRule();
        r.setDailyStart(LocalTime.of(22, 0));
        r.setDailyEnd(LocalTime.of(2, 0));
        r.setDaysOfWeek(List.of(5)); // Friday nights
        ZoneId utc = ZoneId.of("UTC");
        assertThat(r.windowActive(Instant.parse("2026-11-06T23:00:00Z"), utc)).isTrue(); // Friday 23:00
        assertThat(r.windowActive(Instant.parse("2026-11-07T01:00:00Z"), utc)).isTrue(); // Saturday 01:00, still Friday's window
        assertThat(r.windowActive(Instant.parse("2026-11-07T23:00:00Z"), utc)).isFalse(); // Saturday 23:00
        assertThat(r.windowActive(Instant.parse("2026-11-06T21:00:00Z"), utc)).isFalse(); // Friday before the window
        AlertSuppressionRule weekdays = new AlertSuppressionRule();
        weekdays.setDaysOfWeek(List.of(6, 7));
        assertThat(weekdays.windowActive(Instant.parse("2026-11-07T12:00:00Z"), utc)).isTrue(); // Saturday
        assertThat(weekdays.windowActive(Instant.parse("2026-11-04T12:00:00Z"), utc)).isFalse(); // Wednesday
    }
}

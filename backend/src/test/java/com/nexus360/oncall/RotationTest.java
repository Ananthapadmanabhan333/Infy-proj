package com.nexus360.oncall;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus360.oncall.Rotation.Absence;
import com.nexus360.oncall.Rotation.Assignment;
import com.nexus360.oncall.Rotation.Layer;
import com.nexus360.oncall.Rotation.Override;
import com.nexus360.oncall.Rotation.Source;
import com.nexus360.oncall.Rotation.Spec;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Rotation maths: handoffs in the schedule's time zone, DST, CUSTOM shifts, overrides, holidays, unavailability, secondary layer. */
class RotationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Spec daily(ZoneId zone, LocalTime handoff, LocalDate start, List<Long> primary, List<Long> secondary, Set<LocalDate> holidays,
                              Long cover) {
        return new Spec(zone, Rotation.Type.DAILY, handoff, null, null, start, primary, secondary, holidays, cover);
    }

    private static Long who(Spec spec, Layer layer, String instant) {
        Assignment a = Rotation.at(spec, List.of(), List.of(), layer, Instant.parse(instant));
        return a == null ? null : a.userId();
    }

    @Test
    void dailyHandoffHappensAtLocalTimeInTheScheduleTimeZone() {
        Spec s = daily(IST, LocalTime.of(9, 0), LocalDate.of(2026, 3, 2), List.of(1L, 2L, 3L), List.of(), Set.of(), null);
        // 09:00 IST = 03:30 UTC. Before the first handoff nobody is on call.
        assertThat(who(s, Layer.PRIMARY, "2026-03-02T03:29:59Z")).isNull();
        assertThat(who(s, Layer.PRIMARY, "2026-03-02T03:30:00Z")).isEqualTo(1L);
        assertThat(who(s, Layer.PRIMARY, "2026-03-02T23:00:00Z")).isEqualTo(1L); // already 4:30 on the 3rd in IST, before 09:00
        assertThat(who(s, Layer.PRIMARY, "2026-03-03T03:29:59Z")).isEqualTo(1L);
        assertThat(who(s, Layer.PRIMARY, "2026-03-03T03:30:00Z")).isEqualTo(2L);
        assertThat(who(s, Layer.PRIMARY, "2026-03-04T12:00:00Z")).isEqualTo(3L);
        assertThat(who(s, Layer.PRIMARY, "2026-03-05T03:30:00Z")).isEqualTo(1L); // wraps around
        Rotation.Slot slot = Rotation.slot(s, Instant.parse("2026-03-04T12:00:00Z"));
        assertThat(slot.index()).isEqualTo(2);
        assertThat(slot.start()).isEqualTo(Instant.parse("2026-03-04T03:30:00Z"));
        assertThat(slot.end()).isEqualTo(Instant.parse("2026-03-05T03:30:00Z"));
    }

    @Test
    void weeklyHandoffKeepsLocalWallClockAcrossDaylightSaving() {
        // Starts Sunday 1 March 2026 → first handoff Monday 2 March 09:00 EST (14:00 UTC); DST begins 8 March in New York.
        Spec s = new Spec(NEW_YORK, Rotation.Type.WEEKLY, LocalTime.of(9, 0), DayOfWeek.MONDAY, null, LocalDate.of(2026, 3, 1), List.of(10L, 20L),
                List.of(), Set.of(), null);
        Rotation.Slot first = Rotation.slot(s, Instant.parse("2026-03-05T00:00:00Z"));
        assertThat(first.index()).isZero();
        assertThat(first.start()).isEqualTo(Instant.parse("2026-03-02T14:00:00Z"));
        // The next handoff is 09:00 EDT = 13:00 UTC: the shift containing the change is one hour shorter.
        assertThat(first.end()).isEqualTo(Instant.parse("2026-03-09T13:00:00Z"));
        assertThat(Duration.between(first.start(), first.end())).isEqualTo(Duration.ofDays(7).minusHours(1));
        assertThat(who(s, Layer.PRIMARY, "2026-03-09T12:59:59Z")).isEqualTo(10L);
        assertThat(who(s, Layer.PRIMARY, "2026-03-09T13:00:00Z")).isEqualTo(20L);
        assertThat(who(s, Layer.PRIMARY, "2026-03-01T20:00:00Z")).isNull();
    }

    @Test
    void customShiftsAreExactDurations() {
        // 12-hour shifts from 20:00 EST on 7 March (01:00 UTC on the 8th): exact durations, so after DST the local handoff moves to 09:00/21:00.
        Spec s = new Spec(NEW_YORK, Rotation.Type.CUSTOM, LocalTime.of(20, 0), null, 12, LocalDate.of(2026, 3, 7), List.of(1L, 2L), List.of(),
                Set.of(), null);
        assertThat(Rotation.slot(s, Instant.parse("2026-03-08T01:00:00Z")).index()).isZero();
        Rotation.Slot second = Rotation.slot(s, Instant.parse("2026-03-08T14:00:00Z"));
        assertThat(second.index()).isEqualTo(1);
        assertThat(second.start()).isEqualTo(Instant.parse("2026-03-08T13:00:00Z"));
        assertThat(second.start().atZone(NEW_YORK).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(who(s, Layer.PRIMARY, "2026-03-08T12:59:00Z")).isEqualTo(1L);
        assertThat(who(s, Layer.PRIMARY, "2026-03-08T13:00:00Z")).isEqualTo(2L);
    }

    @Test
    void secondaryIsTheNextParticipantOrTheExplicitLayer() {
        Spec shadow = daily(ZoneId.of("UTC"), LocalTime.MIDNIGHT, LocalDate.of(2026, 1, 1), List.of(1L, 2L, 3L), List.of(), Set.of(), null);
        assertThat(who(shadow, Layer.SECONDARY, "2026-01-01T10:00:00Z")).isEqualTo(2L);
        assertThat(who(shadow, Layer.SECONDARY, "2026-01-03T10:00:00Z")).isEqualTo(1L);
        Spec explicit = daily(ZoneId.of("UTC"), LocalTime.MIDNIGHT, LocalDate.of(2026, 1, 1), List.of(1L, 2L), List.of(7L, 8L), Set.of(), null);
        assertThat(who(explicit, Layer.SECONDARY, "2026-01-01T10:00:00Z")).isEqualTo(7L);
        assertThat(who(explicit, Layer.SECONDARY, "2026-01-02T10:00:00Z")).isEqualTo(8L);
        Spec single = daily(ZoneId.of("UTC"), LocalTime.MIDNIGHT, LocalDate.of(2026, 1, 1), List.of(1L), List.of(), Set.of(), null);
        assertThat(who(single, Layer.SECONDARY, "2026-01-01T10:00:00Z")).isNull();
    }

    @Test
    void activeOverridesWinAndTheLatestOverrideWins() {
        Spec s = daily(ZoneId.of("UTC"), LocalTime.MIDNIGHT, LocalDate.of(2026, 1, 1), List.of(1L, 2L), List.of(), Set.of(), null);
        Override a = new Override(100L, Layer.PRIMARY, 9L, Instant.parse("2026-01-01T08:00:00Z"), Instant.parse("2026-01-01T20:00:00Z"));
        Override b = new Override(101L, Layer.PRIMARY, 5L, Instant.parse("2026-01-01T12:00:00Z"), Instant.parse("2026-01-01T14:00:00Z"));
        List<Override> ovs = List.of(a, b);
        Assignment at10 = Rotation.at(s, ovs, List.of(), Layer.PRIMARY, Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(at10.userId()).isEqualTo(9L);
        assertThat(at10.source()).isEqualTo(Source.OVERRIDE);
        assertThat(at10.scheduledUserId()).isEqualTo(1L);
        assertThat(at10.overrideId()).isEqualTo(100L);
        assertThat(Rotation.at(s, ovs, List.of(), Layer.PRIMARY, Instant.parse("2026-01-01T13:00:00Z")).userId()).isEqualTo(5L);
        assertThat(Rotation.at(s, ovs, List.of(), Layer.PRIMARY, Instant.parse("2026-01-01T20:00:00Z")).userId()).isEqualTo(1L); // end is exclusive
        // The secondary never equals the primary: while 9 covers, the next participant after slot 0 (2) stays secondary.
        assertThat(Rotation.at(s, ovs, List.of(), Layer.SECONDARY, Instant.parse("2026-01-01T10:00:00Z")).userId()).isEqualTo(2L);

        List<Assignment> shifts = Rotation.shifts(s, ovs, List.of(), Layer.PRIMARY, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T12:00:00Z"));
        assertThat(shifts).extracting(Assignment::userId).containsExactly(1L, 9L, 5L, 9L, 1L, 2L);
        assertThat(shifts).extracting(Assignment::start).containsExactly(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T08:00:00Z"),
                Instant.parse("2026-01-01T12:00:00Z"), Instant.parse("2026-01-01T14:00:00Z"), Instant.parse("2026-01-01T20:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
        assertThat(shifts.get(5).end()).isEqualTo(Instant.parse("2026-01-02T12:00:00Z"));
    }

    @Test
    void holidaysUseTheHolidayCoverAndTheRotationPrimaryBecomesSecondary() {
        LocalDate holiday = LocalDate.of(2026, 1, 26);
        Spec covered = daily(IST, LocalTime.of(9, 0), LocalDate.of(2026, 1, 1), List.of(1L, 2L), List.of(), Set.of(holiday), 7L);
        // 26 Jan 2026 is slot 25 (odd) → participant 2 by rotation; the holiday spans the whole local day.
        Assignment p = Rotation.at(covered, List.of(), List.of(), Layer.PRIMARY, Instant.parse("2026-01-26T06:00:00Z"));
        assertThat(p.userId()).isEqualTo(7L);
        assertThat(p.source()).isEqualTo(Source.HOLIDAY_COVER);
        assertThat(p.holiday()).isTrue();
        assertThat(p.scheduledUserId()).isEqualTo(2L);
        assertThat(who(covered, Layer.SECONDARY, "2026-01-26T06:00:00Z")).isEqualTo(2L);
        // 00:00 IST on the 26th is 18:30 UTC on the 25th; the day after the holiday the rotation resumes.
        assertThat(who(covered, Layer.PRIMARY, "2026-01-25T18:29:00Z")).isEqualTo(1L);
        assertThat(who(covered, Layer.PRIMARY, "2026-01-25T18:30:00Z")).isEqualTo(7L);
        assertThat(who(covered, Layer.PRIMARY, "2026-01-26T18:30:00Z")).isEqualTo(2L);
        // Without a cover the rotation applies and the shift is flagged as a holiday.
        Spec uncovered = daily(IST, LocalTime.of(9, 0), LocalDate.of(2026, 1, 1), List.of(1L, 2L), List.of(), Set.of(holiday), null);
        Assignment u = Rotation.at(uncovered, List.of(), List.of(), Layer.PRIMARY, Instant.parse("2026-01-26T06:00:00Z"));
        assertThat(u.userId()).isEqualTo(2L);
        assertThat(u.source()).isEqualTo(Source.ROTATION);
        assertThat(u.holiday()).isTrue();
        List<Assignment> shifts = Rotation.shifts(covered, List.of(), List.of(), Layer.PRIMARY, Instant.parse("2026-01-25T12:00:00Z"),
                Instant.parse("2026-01-27T12:00:00Z"));
        assertThat(shifts).extracting(Assignment::userId).containsExactly(1L, 7L, 2L, 1L);
    }

    @Test
    void unavailablePeopleAreSkippedToTheNextAvailableParticipant() {
        Spec s = daily(ZoneId.of("UTC"), LocalTime.MIDNIGHT, LocalDate.of(2026, 1, 1), List.of(1L, 2L, 3L), List.of(), Set.of(), null);
        List<Absence> away = List.of(new Absence(2L, Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-02T12:00:00Z")));
        Assignment a = Rotation.at(s, List.of(), away, Layer.PRIMARY, Instant.parse("2026-01-02T06:00:00Z"));
        assertThat(a.userId()).isEqualTo(3L);
        assertThat(a.source()).isEqualTo(Source.AVAILABILITY);
        assertThat(a.scheduledUserId()).isEqualTo(2L);
        // The secondary skips the person who is primary now: after 3 comes 1.
        assertThat(Rotation.at(s, List.of(), away, Layer.SECONDARY, Instant.parse("2026-01-02T06:00:00Z")).userId()).isEqualTo(1L);
        assertThat(Rotation.at(s, List.of(), away, Layer.PRIMARY, Instant.parse("2026-01-02T12:00:00Z")).userId()).isEqualTo(2L);
        Assignment current = Rotation.current(s, List.of(), away, Layer.PRIMARY, Instant.parse("2026-01-02T15:00:00Z"));
        assertThat(current.start()).isEqualTo(Instant.parse("2026-01-02T12:00:00Z"));
        assertThat(current.end()).isEqualTo(Instant.parse("2026-01-03T00:00:00Z"));
    }
}

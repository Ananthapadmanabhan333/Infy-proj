package com.nexus360.sla;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Pure business-time arithmetic. With a {@code null} calendar the clock runs 24x7.
 * All methods work in whole seconds.
 */
public final class BusinessTimeCalculator {

    /** Guard against misconfigured calendars (e.g. every day a holiday). */
    private static final int MAX_DAYS_SCANNED = 3660;

    private BusinessTimeCalculator() {}

    /** The instant at which {@code seconds} of business time will have elapsed after {@code from}. */
    public static Instant addBusinessSeconds(Instant from, long seconds, BusinessCalendarSpec calendar) {
        if (seconds < 0) {
            throw new IllegalArgumentException("seconds must not be negative");
        }
        if (calendar == null) {
            return from.plusSeconds(seconds);
        }
        long remaining = seconds;
        ZonedDateTime cursor = from.atZone(calendar.zone());
        for (int day = 0; day < MAX_DAYS_SCANNED; day++) {
            LocalDate date = cursor.toLocalDate();
            if (calendar.isWorkingDay(date)) {
                ZonedDateTime open = date.atTime(calendar.start()).atZone(calendar.zone());
                ZonedDateTime close = date.atTime(calendar.end()).atZone(calendar.zone());
                ZonedDateTime segmentStart = cursor.isAfter(open) ? cursor : open;
                if (segmentStart.isBefore(close)) {
                    long available = Duration.between(segmentStart, close).getSeconds();
                    if (remaining <= available) {
                        return segmentStart.plusSeconds(remaining).toInstant();
                    }
                    remaining -= available;
                }
            }
            cursor = date.plusDays(1).atStartOfDay(calendar.zone());
        }
        throw new IllegalStateException("Business calendar has no working time in the next " + MAX_DAYS_SCANNED + " days");
    }

    /** Business seconds elapsed between {@code from} and {@code to}; zero if {@code to} is not after {@code from}. */
    public static long businessSecondsBetween(Instant from, Instant to, BusinessCalendarSpec calendar) {
        if (!to.isAfter(from)) {
            return 0;
        }
        if (calendar == null) {
            return Duration.between(from, to).getSeconds();
        }
        long total = 0;
        ZonedDateTime cursor = from.atZone(calendar.zone());
        ZonedDateTime end = to.atZone(calendar.zone());
        for (int day = 0; day < MAX_DAYS_SCANNED && cursor.isBefore(end); day++) {
            LocalDate date = cursor.toLocalDate();
            if (calendar.isWorkingDay(date)) {
                ZonedDateTime open = date.atTime(calendar.start()).atZone(calendar.zone());
                ZonedDateTime close = date.atTime(calendar.end()).atZone(calendar.zone());
                ZonedDateTime segmentStart = cursor.isAfter(open) ? cursor : open;
                ZonedDateTime segmentEnd = end.isBefore(close) ? end : close;
                if (segmentEnd.isAfter(segmentStart)) {
                    total += Duration.between(segmentStart, segmentEnd).getSeconds();
                }
            }
            cursor = date.plusDays(1).atStartOfDay(calendar.zone());
        }
        return total;
    }
}

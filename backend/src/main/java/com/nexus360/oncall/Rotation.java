package com.nexus360.oncall;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * On-call rotation maths (pure, no persistence; unit-tested in {@code RotationTest}).
 *
 * <p><b>Slots.</b> DAILY and WEEKLY rotations hand off at the schedule's local wall-clock {@code handoffTime} in its time zone, so a
 * handoff stays at e.g. 09:00 local across daylight-saving changes (the shift that contains the change is one hour shorter or
 * longer). DAILY slots start every day at the handoff time from {@code start}; WEEKLY slots start on {@code handoffDay} (the first
 * one on or after {@code start}) every 7 days. CUSTOM slots are exact durations of {@code shiftHours} from {@code start} at the
 * handoff time. Slot {@code k} belongs to participant {@code k mod n} of the layer's ordered list. Before the first slot nobody is
 * on call.</p>
 *
 * <p><b>Precedence</b> for a layer at an instant: an ACTIVE override covering the instant (the most recently created wins) → on a
 * holiday of the schedule's holiday calendar (whole local day) with a holiday cover user: the cover is primary and the rotation's
 * primary becomes secondary → the rotation participant; when that participant is unavailable (personal unavailability) the next
 * available participant in order takes the slot. The SECONDARY layer uses its own participants when it has any; otherwise it is
 * the next available PRIMARY participant after the primary's slot, never the person who is primary at that instant.</p>
 */
public final class Rotation {

    private Rotation() {}

    public enum Type { DAILY, WEEKLY, CUSTOM }

    public enum Layer { PRIMARY, SECONDARY }

    /** Why a person holds a segment. */
    public enum Source { ROTATION, OVERRIDE, HOLIDAY_COVER, AVAILABILITY }

    /**
     * {@code handoffDay}: WEEKLY only; {@code shiftHours}: CUSTOM only; {@code holidays}: local dates of the holiday calendar;
     * {@code holidayCover}: user who is primary on holidays (null = rotation continues).
     */
    public record Spec(ZoneId zone, Type type, LocalTime handoffTime, DayOfWeek handoffDay, Integer shiftHours, LocalDate start,
                       List<Long> primary, List<Long> secondary, Set<LocalDate> holidays, Long holidayCover) {

        public Spec {
            primary = primary == null ? List.of() : List.copyOf(primary);
            secondary = secondary == null ? List.of() : List.copyOf(secondary);
            holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
            handoffTime = handoffTime == null ? LocalTime.of(9, 0) : handoffTime;
        }
    }

    /** An ACTIVE override (others are ignored by the maths). */
    public record Override(Long id, Layer layer, Long userId, Instant start, Instant end) {}

    /** A period in which a person cannot be on call. */
    public record Absence(Long userId, Instant start, Instant end) {}

    /** Slot {@code index} of the rotation and its window. */
    public record Slot(long index, Instant start, Instant end) {}

    /**
     * Who holds a layer during [{@code start}, {@code end}): {@code scheduledUserId} is the rotation participant of the slot (differs
     * from {@code userId} for overrides, holiday cover and availability), {@code overrideId} the override in force.
     */
    public record Assignment(Layer layer, Long userId, Instant start, Instant end, Source source, Long scheduledUserId, boolean holiday,
                             Long overrideId) {}

    // ------------------------------------------------------------------ slots

    /** The rotation slot containing {@code t}, or {@code null} before the rotation starts. */
    public static Slot slot(Spec spec, Instant t) {
        if (spec.type() == Type.CUSTOM) {
            long len = Duration.ofHours(Math.max(1, spec.shiftHours() == null ? 24 : spec.shiftHours())).getSeconds();
            Instant anchor = spec.start().atTime(spec.handoffTime()).atZone(spec.zone()).toInstant();
            if (t.isBefore(anchor)) {
                return null;
            }
            long k = Math.floorDiv(Duration.between(anchor, t).getSeconds(), len);
            return new Slot(k, anchor.plusSeconds(k * len), anchor.plusSeconds((k + 1) * len));
        }
        int period = spec.type() == Type.WEEKLY ? 7 : 1;
        LocalDate anchor = anchorDate(spec);
        Instant first = slotStart(spec, anchor, period, 0);
        if (t.isBefore(first)) {
            return null;
        }
        LocalDate local = t.atZone(spec.zone()).toLocalDate();
        long k = Math.floorDiv(ChronoUnit.DAYS.between(anchor, local), period);
        while (k > 0 && t.isBefore(slotStart(spec, anchor, period, k))) {
            k--;
        }
        while (!t.isBefore(slotStart(spec, anchor, period, k + 1))) {
            k++;
        }
        return new Slot(k, slotStart(spec, anchor, period, k), slotStart(spec, anchor, period, k + 1));
    }

    /** First handoff date: {@code start} (DAILY, CUSTOM) or the first {@code handoffDay} on or after it (WEEKLY). */
    static LocalDate anchorDate(Spec spec) {
        if (spec.type() == Type.WEEKLY) {
            DayOfWeek day = spec.handoffDay() == null ? DayOfWeek.MONDAY : spec.handoffDay();
            return spec.start().with(TemporalAdjusters.nextOrSame(day));
        }
        return spec.start();
    }

    private static Instant slotStart(Spec spec, LocalDate anchor, int period, long k) {
        return anchor.plusDays(k * period).atTime(spec.handoffTime()).atZone(spec.zone()).toInstant();
    }

    public static boolean isHoliday(Spec spec, Instant t) {
        return !spec.holidays().isEmpty() && spec.holidays().contains(t.atZone(spec.zone()).toLocalDate());
    }

    // ------------------------------------------------------------------ who is on call

    /** Who holds {@code layer} at {@code t} (the assignment's window is just [t, t]; use {@link #shifts} for windows), or null. */
    public static Assignment at(Spec spec, List<Override> overrides, List<Absence> absences, Layer layer, Instant t) {
        Assignment primary = resolvePrimary(spec, overrides, absences, t);
        if (layer == Layer.PRIMARY) {
            return primary;
        }
        Override o = override(overrides, Layer.SECONDARY, t);
        if (o != null) {
            return new Assignment(Layer.SECONDARY, o.userId(), t, t, Source.OVERRIDE, null, isHoliday(spec, t), o.id());
        }
        Slot slot = slot(spec, t);
        if (slot == null) {
            return null;
        }
        boolean holiday = isHoliday(spec, t);
        Long primaryUser = primary == null ? null : primary.userId();
        if (primary != null && primary.source() == Source.HOLIDAY_COVER && primary.scheduledUserId() != null
                && !absent(absences, primary.scheduledUserId(), t) && !Objects.equals(primary.scheduledUserId(), primaryUser)) {
            // On a covered holiday the rotation's primary is the backup.
            return new Assignment(Layer.SECONDARY, primary.scheduledUserId(), t, t, Source.HOLIDAY_COVER, primary.scheduledUserId(), true, null);
        }
        List<Long> list = spec.secondary().isEmpty() ? spec.primary() : spec.secondary();
        if (list.isEmpty()) {
            return null;
        }
        long start = spec.secondary().isEmpty() ? slot.index() + 1 : slot.index();
        Long scheduled = list.get(Math.floorMod(start, list.size()));
        Long chosen = firstAvailable(list, start, absences, t, primaryUser);
        if (chosen == null) {
            return null;
        }
        return new Assignment(Layer.SECONDARY, chosen, t, t, Objects.equals(chosen, scheduled) ? Source.ROTATION : Source.AVAILABILITY,
                scheduled, holiday, null);
    }

    private static Assignment resolvePrimary(Spec spec, List<Override> overrides, List<Absence> absences, Instant t) {
        Override o = override(overrides, Layer.PRIMARY, t);
        Slot slot = slot(spec, t);
        boolean holiday = isHoliday(spec, t);
        Long scheduled = slot == null || spec.primary().isEmpty() ? null : spec.primary().get(Math.floorMod(slot.index(), spec.primary().size()));
        if (o != null) {
            return new Assignment(Layer.PRIMARY, o.userId(), t, t, Source.OVERRIDE, scheduled, holiday, o.id());
        }
        if (slot == null || spec.primary().isEmpty()) {
            return null;
        }
        if (holiday && spec.holidayCover() != null) {
            return new Assignment(Layer.PRIMARY, spec.holidayCover(), t, t, Source.HOLIDAY_COVER, scheduled, true, null);
        }
        Long chosen = firstAvailable(spec.primary(), slot.index(), absences, t, null);
        if (chosen == null) {
            chosen = scheduled; // everybody is away: the scheduled person stays responsible
        }
        return new Assignment(Layer.PRIMARY, chosen, t, t, Objects.equals(chosen, scheduled) ? Source.ROTATION : Source.AVAILABILITY,
                scheduled, holiday, null);
    }

    private static Override override(List<Override> overrides, Layer layer, Instant t) {
        Override found = null;
        if (overrides != null) {
            for (Override o : overrides) {
                if (o.layer() == layer && o.userId() != null && !t.isBefore(o.start()) && t.isBefore(o.end())) {
                    found = o; // later entries (created later) win
                }
            }
        }
        return found;
    }

    private static Long firstAvailable(List<Long> list, long start, List<Absence> absences, Instant t, Long exclude) {
        int n = list.size();
        for (int j = 0; j < n; j++) {
            Long u = list.get(Math.floorMod(start + j, n));
            if (!Objects.equals(u, exclude) && !absent(absences, u, t)) {
                return u;
            }
        }
        return null;
    }

    static boolean absent(List<Absence> absences, Long userId, Instant t) {
        if (absences == null || userId == null) {
            return false;
        }
        for (Absence a : absences) {
            if (Objects.equals(a.userId(), userId) && !t.isBefore(a.start()) && t.isBefore(a.end())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ shifts

    /**
     * The layer's assignments in [{@code from}, {@code to}), split at every slot handoff, override, unavailability and holiday
     * boundary and merged where the same person holds the layer for the same reason. Periods without anybody are omitted.
     */
    public static List<Assignment> shifts(Spec spec, List<Override> overrides, List<Absence> absences, Layer layer, Instant from, Instant to) {
        TreeSet<Instant> cuts = new TreeSet<>();
        cuts.add(from);
        cuts.add(to);
        Slot s = slot(spec, from);
        Instant cursor = s == null ? firstSlotStart(spec) : s.end();
        int guard = 0;
        while (cursor != null && cursor.isBefore(to) && guard++ < 10_000) {
            if (cursor.isAfter(from)) {
                cuts.add(cursor);
            }
            Slot next = slot(spec, cursor);
            cursor = next == null ? null : next.end();
        }
        if (overrides != null) {
            overrides.forEach(o -> {
                addCut(cuts, o.start(), from, to);
                addCut(cuts, o.end(), from, to);
            });
        }
        if (absences != null) {
            absences.forEach(a -> {
                addCut(cuts, a.start(), from, to);
                addCut(cuts, a.end(), from, to);
            });
        }
        for (LocalDate d : spec.holidays()) {
            addCut(cuts, d.atStartOfDay(spec.zone()).toInstant(), from, to);
            addCut(cuts, d.plusDays(1).atStartOfDay(spec.zone()).toInstant(), from, to);
        }
        List<Assignment> result = new ArrayList<>();
        Instant prev = null;
        for (Instant cut : cuts) {
            if (prev != null) {
                Assignment a = at(spec, overrides, absences, layer, prev);
                if (a != null && a.userId() != null) {
                    Assignment segment = new Assignment(layer, a.userId(), prev, cut, a.source(), a.scheduledUserId(), a.holiday(), a.overrideId());
                    Assignment last = result.isEmpty() ? null : result.get(result.size() - 1);
                    if (last != null && last.end().equals(prev) && Objects.equals(last.userId(), segment.userId()) && last.source() == segment.source()
                            && Objects.equals(last.overrideId(), segment.overrideId()) && last.holiday() == segment.holiday()) {
                        result.set(result.size() - 1, new Assignment(layer, last.userId(), last.start(), cut, last.source(), last.scheduledUserId(),
                                last.holiday(), last.overrideId()));
                    } else {
                        result.add(segment);
                    }
                }
            }
            prev = cut;
        }
        return result;
    }

    private static Instant firstSlotStart(Spec spec) {
        if (spec.type() == Type.CUSTOM) {
            return spec.start().atTime(spec.handoffTime()).atZone(spec.zone()).toInstant();
        }
        return anchorDate(spec).atTime(spec.handoffTime()).atZone(spec.zone()).toInstant();
    }

    private static void addCut(TreeSet<Instant> cuts, Instant t, Instant from, Instant to) {
        if (t != null && t.isAfter(from) && t.isBefore(to)) {
            cuts.add(t);
        }
    }

    /** The continuous assignment containing {@code t} (start and end found by scanning {@code lookback}/{@code lookahead}). */
    public static Assignment current(Spec spec, List<Override> overrides, List<Absence> absences, Layer layer, Instant t) {
        Duration span = Duration.ofDays(spec.type() == Type.CUSTOM ? 15 : 8);
        for (Assignment a : shifts(spec, overrides, absences, layer, t.minus(span), t.plus(span))) {
            if (!t.isBefore(a.start()) && t.isBefore(a.end())) {
                return a;
            }
        }
        return null;
    }
}

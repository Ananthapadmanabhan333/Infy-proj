package com.nexus360.oncall;

import com.nexus360.alert.AlertSeverity;
import com.nexus360.oncall.EscalationPolicy.Target;
import com.nexus360.oncall.Rotation.Layer;
import com.nexus360.oncall.Rotation.Source;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Request and response shapes of the on-call API (docs/API.md "Release 3 — on-call, major incidents, PIR, analytics, AI"). */
public final class OnCallDtos {

    private OnCallDtos() {}

    // ------------------------------------------------------------------ schedules

    /**
     * {@code handoffTime} "HH:mm" local to {@code timeZone}; {@code handoffDay} 1–7 (WEEKLY); {@code shiftHours} 1–336 (CUSTOM);
     * participants in rotation order (an empty {@code secondaryUserIds} makes the next primary participant the secondary).
     */
    public record ScheduleRequest(
            @NotNull Long teamId,
            @NotBlank @Size(min = 2, max = 120) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 60) String timeZone,
            @NotNull Rotation.Type rotationType,
            @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "must be HH:mm") String handoffTime,
            @Min(1) @Max(7) Integer handoffDay,
            @Min(1) @Max(336) Integer shiftHours,
            @NotNull LocalDate rotationStart,
            @NotNull @Size(min = 1, max = 50) List<@NotNull Long> primaryUserIds,
            @Size(max = 50) List<@NotNull Long> secondaryUserIds,
            Long escalationPolicyId,
            Long holidayCalendarId,
            Long holidayCoverUserId,
            Boolean active) {}

    public record PersonRef(Long id, String name, String email) {}

    /** Who holds a layer now: since/until are the boundaries of the continuous assignment. */
    public record OnCallPerson(Long userId, String name, String email, String phone, Source source, Instant since, Instant until,
                               Long scheduledUserId, String scheduledUserName, boolean holiday) {}

    public record NowEntry(Long scheduleId, String scheduleName, Long teamId, String teamCode, String teamName, String timeZone, Instant at,
                           OnCallPerson primary, OnCallPerson secondary, Long escalationPolicyId, String escalationPolicyName) {}

    public record ScheduleSummary(Long id, String name, Long teamId, String teamCode, String teamName, String timeZone, Rotation.Type rotationType,
                                  String handoffTime, Integer handoffDay, Integer shiftHours, LocalDate rotationStart, int primaryCount,
                                  int secondaryCount, Long escalationPolicyId, String escalationPolicyName, boolean active, boolean demo,
                                  OnCallPerson primaryNow, OnCallPerson secondaryNow) {}

    public record ParticipantDto(int position, Long userId, String userName, String email, boolean active, boolean availableNow) {}

    public record HolidayDto(LocalDate date, String name) {}

    public record ScheduleDetail(ScheduleSummary schedule, String description, List<ParticipantDto> primary, List<ParticipantDto> secondary,
                                 Long holidayCalendarId, String holidayCalendarName, Long holidayCoverUserId, String holidayCoverUserName,
                                 List<HolidayDto> upcomingHolidays, List<OverrideDto> overrides, List<HandoverDto> handovers,
                                 PolicyDto escalationPolicy, boolean canEdit, Instant createdAt, Instant updatedAt) {}

    public record ShiftDto(Layer layer, Long userId, String userName, Instant start, Instant end, Source source, Long scheduledUserId,
                           String scheduledUserName, boolean holiday, Long overrideId) {}

    public record ShiftsResponse(Long scheduleId, String timeZone, Instant from, Instant to, List<ShiftDto> primary, List<ShiftDto> secondary) {}

    public record MyShift(Long scheduleId, String scheduleName, Long teamId, String teamName, Layer layer, Instant start, Instant end, Source source,
                          boolean current) {}

    // ------------------------------------------------------------------ overrides, availability, handovers

    /**
     * {@code offer}: false (default) = an active override for {@code userId} (on-call editors); true = an offer to {@code userId}
     * (or to every participant when null) that the recipient takes. Staff may offer their own shifts.
     */
    public record OverrideRequest(Layer layer, Long userId, Long originalUserId, @NotNull Instant startsAt, @NotNull Instant endsAt,
                                  @Size(max = 500) String reason, Boolean offer) {}

    public record OverrideDto(Long id, Long scheduleId, String scheduleName, Layer layer, Long userId, String userName, Long originalUserId,
                              String originalUserName, Instant startsAt, Instant endsAt, String reason, OnCallOverride.Status status,
                              String createdByName, Instant takenAt, String decidedNote, boolean canTake, boolean demo, Instant createdAt) {}

    public record DecisionNote(@Size(max = 500) String note) {}

    public record UnavailabilityRequest(Long userId, @NotNull Instant startsAt, @NotNull Instant endsAt, @Size(max = 300) String reason) {}

    public record UnavailabilityDto(Long id, Long userId, String userName, Instant startsAt, Instant endsAt, String reason, boolean demo,
                                    Instant createdAt) {}

    public record HandoverRequest(@NotBlank @Size(max = 5000) String body, Long toUserId, Instant shiftStart, Instant shiftEnd) {}

    public record HandoverDto(Long id, Long scheduleId, Long authorId, String authorName, Long toUserId, String toUserName, Instant shiftStart,
                              Instant shiftEnd, String body, boolean demo, Instant createdAt) {}

    // ------------------------------------------------------------------ escalation policies and pages

    public record LevelRequest(@Min(0) @Max(1440) int delayMinutes, @NotNull Target target, Long userId) {}

    public record PolicyRequest(@NotBlank @Size(min = 2, max = 120) String name, @Size(max = 500) String description, Long teamId,
                                AlertSeverity minSeverity, @NotNull @Size(min = 1, max = 10) List<@Valid @NotNull LevelRequest> levels,
                                Boolean active) {}

    public record LevelDto(int level, int delayMinutes, int minutesFromStart, Target target, Long userId, String userName, String label) {}

    public record PolicyDto(Long id, String name, String description, Long teamId, String teamName, AlertSeverity minSeverity, List<LevelDto> levels,
                            boolean active, long scheduleCount, boolean demo, Instant createdAt, Instant updatedAt) {}

    public record PageDto(Long id, Long alertId, String alertNumber, String alertTitle, String severity, String alertStatus, int level,
                          Target target, Long userId, String userName, Long scheduleId, String scheduleName, Instant pagedAt,
                          Instant acknowledgedAt, Double ackMinutes, boolean demo) {}
}

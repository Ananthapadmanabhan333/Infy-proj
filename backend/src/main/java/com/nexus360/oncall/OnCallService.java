package com.nexus360.oncall;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.oncall.EscalationPolicy.Level;
import com.nexus360.oncall.EscalationPolicy.Target;
import com.nexus360.oncall.OnCallDtos.DecisionNote;
import com.nexus360.oncall.OnCallDtos.HandoverDto;
import com.nexus360.oncall.OnCallDtos.HandoverRequest;
import com.nexus360.oncall.OnCallDtos.HolidayDto;
import com.nexus360.oncall.OnCallDtos.LevelDto;
import com.nexus360.oncall.OnCallDtos.MyShift;
import com.nexus360.oncall.OnCallDtos.NowEntry;
import com.nexus360.oncall.OnCallDtos.OnCallPerson;
import com.nexus360.oncall.OnCallDtos.OverrideDto;
import com.nexus360.oncall.OnCallDtos.OverrideRequest;
import com.nexus360.oncall.OnCallDtos.PageDto;
import com.nexus360.oncall.OnCallDtos.ParticipantDto;
import com.nexus360.oncall.OnCallDtos.PolicyDto;
import com.nexus360.oncall.OnCallDtos.PolicyRequest;
import com.nexus360.oncall.OnCallDtos.ScheduleDetail;
import com.nexus360.oncall.OnCallDtos.ScheduleRequest;
import com.nexus360.oncall.OnCallDtos.ScheduleSummary;
import com.nexus360.oncall.OnCallDtos.ShiftDto;
import com.nexus360.oncall.OnCallDtos.ShiftsResponse;
import com.nexus360.oncall.OnCallDtos.UnavailabilityDto;
import com.nexus360.oncall.OnCallDtos.UnavailabilityRequest;
import com.nexus360.oncall.Rotation.Assignment;
import com.nexus360.oncall.Rotation.Layer;
import com.nexus360.security.Actor;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRepository;
import com.nexus360.team.TeamRouting;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * On-call schedules (docs/RELEASE3_SPEC.md §5 BE-4): CRUD, "who is on call now / at a time", computed shifts, overrides that are
 * offered and taken, personal unavailability, handover notes, escalation policies and the page log. The rotation maths live in
 * {@link Rotation}; this service loads a schedule's participants, active overrides, unavailability and holiday calendar for a window
 * and feeds them in.
 */
@Service
@RequiredArgsConstructor
public class OnCallService {

    static final Set<Role> EDITORS = Set.of(Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.IT_MANAGER, Role.INCIDENT_MANAGER);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Duration MAX_OVERRIDE = Duration.ofDays(31);
    private static final Duration MAX_SHIFT_RANGE = Duration.ofDays(62);

    private final OnCallScheduleRepository schedules;
    private final OnCallParticipantRepository participants;
    private final OnCallOverrideRepository overrides;
    private final OnCallUnavailabilityRepository unavailability;
    private final OnCallHandoverRepository handovers;
    private final EscalationPolicyRepository policies;
    private final TeamRepository teams;
    private final TeamRouting teamRouting;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** A schedule with everything the rotation maths need for one window. */
    public record Loaded(OnCallSchedule schedule, List<OnCallParticipant> participants, Rotation.Spec spec, List<Rotation.Override> overrides,
                         List<Rotation.Absence> absences) {}

    public static boolean isEditor(Actor actor) {
        return actor.isSystem() || EDITORS.contains(actor.role());
    }

    // ------------------------------------------------------------------ loading

    /** Loads the schedule's rotation inputs for [from, to). */
    @Transactional(readOnly = true)
    public Loaded load(OnCallSchedule s, Instant from, Instant to) {
        List<OnCallParticipant> parts = participants.findByScheduleIdOrderByLayerAscPositionAsc(s.getId());
        return load(s, parts, from, to);
    }

    private Loaded load(OnCallSchedule s, List<OnCallParticipant> parts, Instant from, Instant to) {
        ZoneId zone = zone(s.getTimeZone());
        List<Long> primary = parts.stream().filter(p -> p.getLayer() == Layer.PRIMARY).sorted(Comparator.comparingInt(OnCallParticipant::getPosition))
                .map(p -> p.getUser().getId()).toList();
        List<Long> secondary = parts.stream().filter(p -> p.getLayer() == Layer.SECONDARY)
                .sorted(Comparator.comparingInt(OnCallParticipant::getPosition)).map(p -> p.getUser().getId()).toList();
        Set<LocalDate> holidays = new HashSet<>();
        if (s.getHolidayCalendarId() != null) {
            LocalDate a = LocalDate.ofInstant(from, zone).minusDays(1);
            LocalDate b = LocalDate.ofInstant(to, zone).plusDays(1);
            holidays.addAll(jdbc.queryForList("select holiday_date from calendar_holidays where calendar_id = ? and holiday_date between ? and ?",
                    java.sql.Date.class, s.getHolidayCalendarId(), java.sql.Date.valueOf(a), java.sql.Date.valueOf(b)).stream()
                    .map(java.sql.Date::toLocalDate).toList());
        }
        Long cover = s.getHolidayCoverUser() == null ? null : s.getHolidayCoverUser().getId();
        Rotation.Spec spec = new Rotation.Spec(zone, s.getRotationType(), s.getHandoffTime(),
                s.getHandoffDay() == null ? null : DayOfWeek.of(s.getHandoffDay()), s.getShiftHours(), s.getRotationStart(), primary, secondary,
                holidays, cover);
        List<Rotation.Override> active = overrides.findOverlapping(List.of(s.getId()), List.of(OnCallOverride.Status.ACTIVE), from, to).stream()
                .filter(o -> o.getUser() != null)
                .map(o -> new Rotation.Override(o.getId(), o.getLayer(), o.getUser().getId(), o.getStartsAt(), o.getEndsAt())).toList();
        Set<Long> people = new LinkedHashSet<>(primary);
        people.addAll(secondary);
        List<Rotation.Absence> absences = people.isEmpty() ? List.of() : unavailability.findOverlapping(people, from, to).stream()
                .map(a -> new Rotation.Absence(a.getUser().getId(), a.getStartsAt(), a.getEndsAt())).toList();
        return new Loaded(s, parts, spec, active, absences);
    }

    /** Who holds the layer of the schedule at {@code t} (continuous assignment with its boundaries), or null. */
    @Transactional(readOnly = true)
    public Assignment whoAt(OnCallSchedule s, Layer layer, Instant t) {
        Duration span = Duration.ofDays(s.getRotationType() == Rotation.Type.CUSTOM ? 15 : 8);
        Loaded l = load(s, t.minus(span), t.plus(span));
        return Rotation.current(l.spec(), l.overrides(), l.absences(), layer, t);
    }

    /** Active schedules of a team, oldest first. */
    @Transactional(readOnly = true)
    public List<OnCallSchedule> activeSchedulesOfTeam(Long teamId) {
        return teamId == null ? List.of() : schedules.findByTeamIdAndActiveTrueOrderByIdAsc(teamId);
    }

    // ------------------------------------------------------------------ who is on call

    /** Who is on call at {@code at} (default now) for every active schedule, optionally of one team. */
    @Transactional(readOnly = true)
    public List<NowEntry> onCall(Long teamId, Instant at) {
        Instant t = at == null ? clock.instant() : at;
        List<OnCallSchedule> list = teamId == null ? schedules.findByActiveTrueOrderByNameAsc() : schedules.findByTeamIdAndActiveTrueOrderByIdAsc(teamId);
        List<NowEntry> result = new ArrayList<>();
        for (OnCallSchedule s : list) {
            result.add(nowEntry(s, t));
        }
        return result;
    }

    private NowEntry nowEntry(OnCallSchedule s, Instant t) {
        Duration span = Duration.ofDays(s.getRotationType() == Rotation.Type.CUSTOM ? 15 : 8);
        Loaded l = load(s, t.minus(span), t.plus(span));
        Assignment p = Rotation.current(l.spec(), l.overrides(), l.absences(), Layer.PRIMARY, t);
        Assignment sec = Rotation.current(l.spec(), l.overrides(), l.absences(), Layer.SECONDARY, t);
        Map<Long, User> people = people(p, sec);
        Team team = s.getTeam();
        return new NowEntry(s.getId(), s.getName(), team.getId(), team.getCode(), team.getName(), s.getTimeZone(), t, person(p, people),
                person(sec, people), s.getEscalationPolicy() == null ? null : s.getEscalationPolicy().getId(),
                s.getEscalationPolicy() == null ? null : s.getEscalationPolicy().getName());
    }

    private Map<Long, User> people(Assignment... list) {
        Set<Long> ids = new HashSet<>();
        for (Assignment a : list) {
            if (a != null) {
                ids.add(a.userId());
                if (a.scheduledUserId() != null) {
                    ids.add(a.scheduledUserId());
                }
            }
        }
        return ids.isEmpty() ? Map.of() : users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static OnCallPerson person(Assignment a, Map<Long, User> people) {
        if (a == null) {
            return null;
        }
        User u = people.get(a.userId());
        User sched = a.scheduledUserId() == null ? null : people.get(a.scheduledUserId());
        return new OnCallPerson(a.userId(), u == null ? null : u.getFullName(), u == null ? null : u.getEmail(), u == null ? null : u.getPhone(),
                a.source(), a.start(), a.end(), a.scheduledUserId(), sched == null ? null : sched.getFullName(), a.holiday());
    }

    /** Computed shifts of both layers in [from, to) (≤ 62 days; default the next 14 days). */
    @Transactional(readOnly = true)
    public ShiftsResponse shifts(Long id, Instant from, Instant to) {
        OnCallSchedule s = find(id);
        Instant a = from == null ? clock.instant() : from;
        Instant b = to == null ? a.plus(Duration.ofDays(14)) : to;
        if (!b.isAfter(a)) {
            throw ApiException.badRequest("'to' must be after 'from'");
        }
        if (Duration.between(a, b).compareTo(MAX_SHIFT_RANGE) > 0) {
            throw ApiException.badRequest("The range may cover at most 62 days");
        }
        Loaded l = load(s, a, b);
        List<Assignment> primary = Rotation.shifts(l.spec(), l.overrides(), l.absences(), Layer.PRIMARY, a, b);
        List<Assignment> secondary = Rotation.shifts(l.spec(), l.overrides(), l.absences(), Layer.SECONDARY, a, b);
        Set<Long> ids = new HashSet<>();
        primary.forEach(x -> collect(ids, x));
        secondary.forEach(x -> collect(ids, x));
        Map<Long, User> people = ids.isEmpty() ? Map.of() : users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return new ShiftsResponse(s.getId(), s.getTimeZone(), a, b, primary.stream().map(x -> shift(x, people)).toList(),
                secondary.stream().map(x -> shift(x, people)).toList());
    }

    private static void collect(Set<Long> ids, Assignment a) {
        ids.add(a.userId());
        if (a.scheduledUserId() != null) {
            ids.add(a.scheduledUserId());
        }
    }

    private static ShiftDto shift(Assignment a, Map<Long, User> people) {
        User u = people.get(a.userId());
        User sched = a.scheduledUserId() == null ? null : people.get(a.scheduledUserId());
        return new ShiftDto(a.layer(), a.userId(), u == null ? null : u.getFullName(), a.start(), a.end(), a.source(), a.scheduledUserId(),
                sched == null ? null : sched.getFullName(), a.holiday(), a.overrideId());
    }

    /** My shifts (either layer) in the next {@code days} days, including the one I am on now, soonest first. */
    @Transactional(readOnly = true)
    public List<MyShift> myShifts(Long userId, int days, int limit) {
        Instant now = clock.instant();
        Instant to = now.plus(Duration.ofDays(Math.max(1, Math.min(days, 62))));
        Set<Long> scheduleIds = new LinkedHashSet<>(participants.findScheduleIdsOfUser(userId));
        jdbc.queryForList("select schedule_id from oncall_overrides where user_id = ? and status = 'ACTIVE' and ends_at > ?", Long.class, userId,
                Timestamp.from(now)).forEach(scheduleIds::add);
        jdbc.queryForList("select id from oncall_schedules where holiday_cover_user_id = ?", Long.class, userId).forEach(scheduleIds::add);
        List<MyShift> result = new ArrayList<>();
        for (Long sid : scheduleIds) {
            OnCallSchedule s = schedules.findWithDetailsById(sid).orElse(null);
            if (s == null || !s.isActive()) {
                continue;
            }
            Loaded l = load(s, now, to);
            for (Layer layer : Layer.values()) {
                for (Assignment a : Rotation.shifts(l.spec(), l.overrides(), l.absences(), layer, now, to)) {
                    if (Objects.equals(a.userId(), userId)) {
                        Instant start = a.start().equals(now) ? Rotation.current(l.spec(), l.overrides(), l.absences(), layer, now) == null
                                ? a.start() : Rotation.current(l.spec(), l.overrides(), l.absences(), layer, now).start() : a.start();
                        result.add(new MyShift(s.getId(), s.getName(), s.getTeam().getId(), s.getTeam().getName(), layer, start, a.end(), a.source(),
                                !now.isBefore(a.start()) && now.isBefore(a.end())));
                    }
                }
            }
        }
        result.sort(Comparator.comparing(MyShift::start).thenComparing(MyShift::layer));
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    // ------------------------------------------------------------------ schedules

    @Transactional(readOnly = true)
    public List<ScheduleSummary> list(Long teamId, Boolean active) {
        Instant now = clock.instant();
        return schedules.findAllByOrderByNameAsc().stream()
                .filter(s -> teamId == null || Objects.equals(s.getTeam().getId(), teamId))
                .filter(s -> active == null || s.isActive() == active)
                .map(s -> summary(s, participants.findByScheduleIdOrderByLayerAscPositionAsc(s.getId()), now)).toList();
    }

    @Transactional(readOnly = true)
    public ScheduleDetail get(Long id, Actor actor) {
        OnCallSchedule s = find(id);
        Instant now = clock.instant();
        List<OnCallParticipant> parts = participants.findByScheduleIdOrderByLayerAscPositionAsc(id);
        Loaded l = load(s, parts, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));
        List<ParticipantDto> primary = new ArrayList<>();
        List<ParticipantDto> secondary = new ArrayList<>();
        for (OnCallParticipant p : parts) {
            ParticipantDto dto = new ParticipantDto(p.getPosition(), p.getUser().getId(), p.getUser().getFullName(), p.getUser().getEmail(),
                    p.getUser().isActive(), !Rotation.absent(l.absences(), p.getUser().getId(), now));
            (p.getLayer() == Layer.PRIMARY ? primary : secondary).add(dto);
        }
        String calendarName = s.getHolidayCalendarId() == null ? null : jdbc.queryForList("select name from business_hours_calendars where id = ?",
                String.class, s.getHolidayCalendarId()).stream().findFirst().orElse(null);
        List<HolidayDto> holidays = s.getHolidayCalendarId() == null ? List.of() : jdbc.query("select holiday_date, name from calendar_holidays "
                + "where calendar_id = ? and holiday_date >= ? order by holiday_date limit 10", (rs, n) -> new HolidayDto(rs.getDate(1).toLocalDate(),
                rs.getString(2)), s.getHolidayCalendarId(), java.sql.Date.valueOf(LocalDate.ofInstant(now, zone(s.getTimeZone()))));
        List<OverrideDto> ovs = overrides.findOverlapping(List.of(id), List.of(OnCallOverride.Status.OFFERED, OnCallOverride.Status.ACTIVE), now,
                now.plus(Duration.ofDays(365))).stream().map(o -> overrideDto(o, s, actor, now)).toList();
        List<HandoverDto> notes = handovers.findByScheduleIdOrderByCreatedAtDescIdDesc(id, PageRequest.of(0, 10)).stream().map(this::handoverDto).toList();
        return new ScheduleDetail(summary(s, parts, now), s.getDescription(), primary, secondary, s.getHolidayCalendarId(), calendarName,
                s.getHolidayCoverUser() == null ? null : s.getHolidayCoverUser().getId(),
                s.getHolidayCoverUser() == null ? null : s.getHolidayCoverUser().getFullName(), holidays, ovs, notes,
                s.getEscalationPolicy() == null ? null : policyDto(s.getEscalationPolicy()), isEditor(actor), s.getCreatedAt(), s.getUpdatedAt());
    }

    private ScheduleSummary summary(OnCallSchedule s, List<OnCallParticipant> parts, Instant now) {
        Duration span = Duration.ofDays(s.getRotationType() == Rotation.Type.CUSTOM ? 15 : 8);
        Loaded l = load(s, parts, now.minus(span), now.plus(span));
        Assignment p = s.isActive() ? Rotation.current(l.spec(), l.overrides(), l.absences(), Layer.PRIMARY, now) : null;
        Assignment sec = s.isActive() ? Rotation.current(l.spec(), l.overrides(), l.absences(), Layer.SECONDARY, now) : null;
        Map<Long, User> people = people(p, sec);
        Team t = s.getTeam();
        return new ScheduleSummary(s.getId(), s.getName(), t.getId(), t.getCode(), t.getName(), s.getTimeZone(), s.getRotationType(),
                s.getHandoffTime().format(HHMM), s.getHandoffDay(), s.getShiftHours(), s.getRotationStart(),
                (int) parts.stream().filter(x -> x.getLayer() == Layer.PRIMARY).count(), (int) parts.stream().filter(x -> x.getLayer() == Layer.SECONDARY).count(),
                s.getEscalationPolicy() == null ? null : s.getEscalationPolicy().getId(), s.getEscalationPolicy() == null ? null : s.getEscalationPolicy().getName(),
                s.isActive(), s.isDemo(), person(p, people), person(sec, people));
    }

    @Transactional
    public ScheduleDetail create(ScheduleRequest r, Actor actor) {
        if (schedules.existsByNameIgnoreCase(r.name().trim())) {
            throw ApiException.conflict("A schedule named '" + r.name().trim() + "' already exists");
        }
        OnCallSchedule s = new OnCallSchedule();
        s.setCreatedById(actor.id());
        apply(s, r);
        schedules.save(s);
        replaceParticipants(s, r);
        audit.record("ONCALL_SCHEDULE_CREATED", "ONCALL_SCHEDULE", s.getId(), Map.of("name", s.getName(), "team", s.getTeam().getCode(),
                "rotation", s.getRotationType(), "participants", r.primaryUserIds().size()));
        return get(s.getId(), actor);
    }

    @Transactional
    public ScheduleDetail update(Long id, ScheduleRequest r, Actor actor) {
        OnCallSchedule s = find(id);
        if (schedules.existsByNameIgnoreCaseAndIdNot(r.name().trim(), id)) {
            throw ApiException.conflict("A schedule named '" + r.name().trim() + "' already exists");
        }
        apply(s, r);
        replaceParticipants(s, r);
        audit.record("ONCALL_SCHEDULE_UPDATED", "ONCALL_SCHEDULE", s.getId(), Map.of("name", s.getName(), "rotation", s.getRotationType(),
                "active", s.isActive()));
        return get(s.getId(), actor);
    }

    private void apply(OnCallSchedule s, ScheduleRequest r) {
        Team team = teams.findWithDetailsById(r.teamId()).filter(Team::isActive)
                .orElseThrow(() -> ApiException.invalidField("teamId", "Team " + r.teamId() + " does not exist or is inactive"));
        zone(r.timeZone());
        s.setTeam(team);
        s.setName(r.name().trim());
        s.setDescription(blankToNull(r.description()));
        s.setTimeZone(r.timeZone().trim());
        s.setRotationType(r.rotationType());
        s.setHandoffTime(r.handoffTime() == null ? LocalTime.of(9, 0) : LocalTime.parse(r.handoffTime()));
        s.setHandoffDay(r.rotationType() == Rotation.Type.WEEKLY ? (r.handoffDay() == null ? 1 : r.handoffDay()) : null);
        if (r.rotationType() == Rotation.Type.CUSTOM && r.shiftHours() == null) {
            throw ApiException.invalidField("shiftHours", "A CUSTOM rotation needs shiftHours (1-336)");
        }
        s.setShiftHours(r.rotationType() == Rotation.Type.CUSTOM ? r.shiftHours() : null);
        s.setRotationStart(r.rotationStart());
        s.setEscalationPolicy(r.escalationPolicyId() == null ? null : policies.findById(r.escalationPolicyId())
                .orElseThrow(() -> ApiException.invalidField("escalationPolicyId", "Escalation policy " + r.escalationPolicyId() + " does not exist")));
        if (r.holidayCalendarId() != null && jdbc.queryForObject("select count(*) from business_hours_calendars where id = ?", Long.class,
                r.holidayCalendarId()) == 0) {
            throw ApiException.invalidField("holidayCalendarId", "Calendar " + r.holidayCalendarId() + " does not exist");
        }
        s.setHolidayCalendarId(r.holidayCalendarId());
        s.setHolidayCoverUser(r.holidayCoverUserId() == null ? null : staff(r.holidayCoverUserId(), "holidayCoverUserId"));
        s.setActive(r.active() == null || r.active());
    }

    private void replaceParticipants(OnCallSchedule s, ScheduleRequest r) {
        List<User> primary = distinctStaff(r.primaryUserIds(), "primaryUserIds");
        List<User> secondary = distinctStaff(r.secondaryUserIds() == null ? List.of() : r.secondaryUserIds(), "secondaryUserIds");
        participants.deleteBySchedule(s.getId());
        participants.flush();
        int pos = 0;
        for (User u : primary) {
            participants.save(participant(s, Layer.PRIMARY, pos++, u));
        }
        pos = 0;
        for (User u : secondary) {
            participants.save(participant(s, Layer.SECONDARY, pos++, u));
        }
    }

    private static OnCallParticipant participant(OnCallSchedule s, Layer layer, int pos, User u) {
        OnCallParticipant p = new OnCallParticipant();
        p.setScheduleId(s.getId());
        p.setLayer(layer);
        p.setPosition(pos);
        p.setUser(u);
        return p;
    }

    private List<User> distinctStaff(List<Long> ids, String field) {
        if (new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.invalidField(field, "A person can appear only once per layer");
        }
        List<User> result = new ArrayList<>();
        for (Long id : ids) {
            result.add(staff(id, field));
        }
        return result;
    }

    private User staff(Long id, String field) {
        return users.findById(id).filter(u -> u.isActive() && u.getRole().isStaff())
                .orElseThrow(() -> ApiException.invalidField(field, "User " + id + " is not an active staff member"));
    }

    // ------------------------------------------------------------------ overrides

    @Transactional(readOnly = true)
    public List<OverrideDto> listOverrides(Long scheduleId, Actor actor, boolean includePast) {
        OnCallSchedule s = find(scheduleId);
        Instant now = clock.instant();
        List<OnCallOverride.Status> statuses = includePast ? List.of(OnCallOverride.Status.values()) : List.of(OnCallOverride.Status.OFFERED,
                OnCallOverride.Status.ACTIVE);
        return overrides.findOverlapping(List.of(scheduleId), statuses, includePast ? now.minus(Duration.ofDays(90)) : now,
                now.plus(Duration.ofDays(365))).stream().map(o -> overrideDto(o, s, actor, now)).toList();
    }

    /** Offers I can take (addressed to me, or open offers of schedules I take part in). */
    @Transactional(readOnly = true)
    public List<OverrideDto> offersFor(Actor actor) {
        Instant now = clock.instant();
        List<Long> mine = participants.findScheduleIdsOfUser(actor.id());
        return overrides.findOffersFor(actor.id(), mine.isEmpty() ? List.of(-1L) : mine, now).stream()
                .filter(o -> o.getOriginalUser() == null || !Objects.equals(o.getOriginalUser().getId(), actor.id()))
                .map(o -> overrideDto(o, o.getSchedule(), actor, now)).toList();
    }

    @Transactional
    public OverrideDto createOverride(Long scheduleId, OverrideRequest r, Actor actor) {
        OnCallSchedule s = find(scheduleId);
        Instant now = clock.instant();
        boolean offer = Boolean.TRUE.equals(r.offer());
        boolean editor = isEditor(actor);
        if (!r.endsAt().isAfter(r.startsAt())) {
            throw ApiException.invalidField("endsAt", "endsAt must be after startsAt");
        }
        if (!r.endsAt().isAfter(now)) {
            throw ApiException.invalidField("endsAt", "The override must end in the future");
        }
        if (Duration.between(r.startsAt(), r.endsAt()).compareTo(MAX_OVERRIDE) > 0) {
            throw ApiException.invalidField("endsAt", "An override may cover at most 31 days");
        }
        if (!offer && !editor) {
            throw ApiException.forbidden("Only on-call editors set overrides directly; offer your shift instead");
        }
        boolean participant = participants.existsByScheduleIdAndUserId(scheduleId, actor.id());
        if (offer && !editor && !participant) {
            throw ApiException.forbidden("Only participants of " + s.getName() + " (or on-call editors) can offer its shifts");
        }
        OnCallOverride o = new OnCallOverride();
        o.setSchedule(s);
        o.setLayer(r.layer() == null ? Layer.PRIMARY : r.layer());
        o.setStartsAt(r.startsAt());
        o.setEndsAt(r.endsAt());
        o.setReason(blankToNull(r.reason()));
        o.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        User original = r.originalUserId() == null ? null : staff(r.originalUserId(), "originalUserId");
        if (offer && !editor) {
            original = users.getReferenceById(actor.id()); // staff offer their own shift
        }
        o.setOriginalUser(original);
        if (offer) {
            User target = r.userId() == null ? null : staff(r.userId(), "userId");
            if (target != null && original != null && Objects.equals(target.getId(), original.getId())) {
                throw ApiException.invalidField("userId", "The shift cannot be offered to the person it belongs to");
            }
            o.setUser(target);
            o.setStatus(OnCallOverride.Status.OFFERED);
        } else {
            if (r.userId() == null) {
                throw ApiException.invalidField("userId", "Who covers the shift is required");
            }
            o.setUser(staff(r.userId(), "userId"));
            o.setStatus(OnCallOverride.Status.ACTIVE);
            o.setTakenAt(now);
        }
        overrides.save(o);
        String window = r.startsAt() + " – " + r.endsAt();
        if (offer) {
            Set<Long> to = new LinkedHashSet<>();
            if (o.getUser() != null) {
                to.add(o.getUser().getId());
            } else {
                participants.findByScheduleIdOrderByLayerAscPositionAsc(scheduleId).forEach(p -> to.add(p.getUser().getId()));
            }
            if (original != null) {
                to.remove(original.getId());
            }
            notifications.notifyUsers(to, "ONCALL_OVERRIDE", "On-call cover requested: " + s.getName(), actor.name() + " asks for cover of the "
                    + o.getLayer().name().toLowerCase() + " shift " + window + (o.getReason() == null ? "" : " (" + o.getReason() + ")"),
                    "/on-call/" + s.getId(), Severity.INFO);
        } else {
            notifications.notifyUser(o.getUser().getId(), "ONCALL_OVERRIDE", "You cover on-call: " + s.getName(), "You are "
                    + o.getLayer().name().toLowerCase() + " on call " + window + ".", "/on-call/" + s.getId(), Severity.INFO);
        }
        audit.record(offer ? "ONCALL_OVERRIDE_OFFERED" : "ONCALL_OVERRIDE_CREATED", "ONCALL_OVERRIDE", o.getId(), Map.of("schedule", s.getName(),
                "layer", o.getLayer(), "user", o.getUser() == null ? "any participant" : o.getUser().getEmail(), "startsAt", r.startsAt().toString(),
                "endsAt", r.endsAt().toString()));
        return overrideDto(o, s, actor, now);
    }

    @Transactional
    public OverrideDto take(Long id, Actor actor) {
        OnCallOverride o = loadOverride(id);
        Instant now = clock.instant();
        if (o.getStatus() != OnCallOverride.Status.OFFERED) {
            throw ApiException.conflict("This override is " + o.getStatus().name().toLowerCase() + ", not offered");
        }
        if (!o.getEndsAt().isAfter(now)) {
            throw ApiException.conflict("The offered shift has already ended");
        }
        if (!canTake(o, actor)) {
            throw ApiException.forbidden("This offer is not addressed to you");
        }
        o.setUser(users.findById(actor.id()).orElseThrow());
        o.setStatus(OnCallOverride.Status.ACTIVE);
        o.setTakenAt(now);
        Set<Long> to = new LinkedHashSet<>();
        if (o.getCreatedBy() != null) {
            to.add(o.getCreatedBy().getId());
        }
        if (o.getOriginalUser() != null) {
            to.add(o.getOriginalUser().getId());
        }
        notifications.notifyUsers(to, "ONCALL_OVERRIDE", "On-call cover taken: " + o.getSchedule().getName(), actor.name() + " covers the "
                + o.getLayer().name().toLowerCase() + " shift " + o.getStartsAt() + " – " + o.getEndsAt() + ".", "/on-call/" + o.getSchedule().getId(),
                Severity.INFO);
        audit.record("ONCALL_OVERRIDE_TAKEN", "ONCALL_OVERRIDE", o.getId(), Map.of("schedule", o.getSchedule().getName(), "user", actor.email()));
        return overrideDto(o, o.getSchedule(), actor, now);
    }

    @Transactional
    public OverrideDto decline(Long id, DecisionNote note, Actor actor) {
        OnCallOverride o = loadOverride(id);
        if (o.getStatus() != OnCallOverride.Status.OFFERED) {
            throw ApiException.conflict("Only offered overrides can be declined");
        }
        if (o.getUser() == null || !Objects.equals(o.getUser().getId(), actor.id())) {
            throw ApiException.forbidden("Only the person the offer is addressed to can decline it");
        }
        o.setStatus(OnCallOverride.Status.DECLINED);
        o.setDecidedNote(note == null ? null : blankToNull(note.note()));
        if (o.getCreatedBy() != null) {
            notifications.notifyUser(o.getCreatedBy().getId(), "ONCALL_OVERRIDE", "On-call cover declined: " + o.getSchedule().getName(),
                    actor.name() + " cannot cover " + o.getStartsAt() + " – " + o.getEndsAt() + (o.getDecidedNote() == null ? "" : ": " + o.getDecidedNote()),
                    "/on-call/" + o.getSchedule().getId(), Severity.INFO);
        }
        audit.record("ONCALL_OVERRIDE_DECLINED", "ONCALL_OVERRIDE", o.getId(), Map.of("schedule", o.getSchedule().getName()));
        return overrideDto(o, o.getSchedule(), actor, clock.instant());
    }

    @Transactional
    public OverrideDto cancel(Long id, Actor actor) {
        OnCallOverride o = loadOverride(id);
        if (o.getStatus() != OnCallOverride.Status.OFFERED && o.getStatus() != OnCallOverride.Status.ACTIVE) {
            throw ApiException.conflict("This override is already " + o.getStatus().name().toLowerCase());
        }
        boolean own = (o.getCreatedBy() != null && Objects.equals(o.getCreatedBy().getId(), actor.id()))
                || (o.getOriginalUser() != null && Objects.equals(o.getOriginalUser().getId(), actor.id()));
        if (!own && !isEditor(actor)) {
            throw ApiException.forbidden("Only the person who created the override or an on-call editor can cancel it");
        }
        o.setStatus(OnCallOverride.Status.CANCELLED);
        if (o.getUser() != null) {
            notifications.notifyUser(o.getUser().getId(), "ONCALL_OVERRIDE", "On-call override cancelled: " + o.getSchedule().getName(),
                    "The " + o.getLayer().name().toLowerCase() + " cover " + o.getStartsAt() + " – " + o.getEndsAt() + " was cancelled.",
                    "/on-call/" + o.getSchedule().getId(), Severity.INFO);
        }
        audit.record("ONCALL_OVERRIDE_CANCELLED", "ONCALL_OVERRIDE", o.getId(), Map.of("schedule", o.getSchedule().getName()));
        return overrideDto(o, o.getSchedule(), actor, clock.instant());
    }

    private boolean canTake(OnCallOverride o, Actor actor) {
        if (actor.id() == null || o.getStatus() != OnCallOverride.Status.OFFERED || !actor.isStaff()) {
            return false;
        }
        if (o.getOriginalUser() != null && Objects.equals(o.getOriginalUser().getId(), actor.id())) {
            return false;
        }
        if (o.getUser() != null) {
            return Objects.equals(o.getUser().getId(), actor.id());
        }
        Long sid = o.getSchedule().getId();
        return participants.existsByScheduleIdAndUserId(sid, actor.id())
                || teamRouting.teamIdsOf(actor.id()).contains(o.getSchedule().getTeam().getId());
    }

    private OverrideDto overrideDto(OnCallOverride o, OnCallSchedule s, Actor actor, Instant now) {
        return new OverrideDto(o.getId(), s.getId(), s.getName(), o.getLayer(), o.getUser() == null ? null : o.getUser().getId(),
                o.getUser() == null ? null : o.getUser().getFullName(), o.getOriginalUser() == null ? null : o.getOriginalUser().getId(),
                o.getOriginalUser() == null ? null : o.getOriginalUser().getFullName(), o.getStartsAt(), o.getEndsAt(), o.getReason(), o.getStatus(),
                o.getCreatedBy() == null ? "SA Automation" : o.getCreatedBy().getFullName(), o.getTakenAt(), o.getDecidedNote(),
                actor != null && o.getEndsAt().isAfter(now) && canTake(o, actor), o.isDemo(), o.getCreatedAt());
    }

    private OnCallOverride loadOverride(Long id) {
        return overrides.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Override", id));
    }

    // ------------------------------------------------------------------ availability

    @Transactional(readOnly = true)
    public List<UnavailabilityDto> availability(Long userId, boolean all, Actor actor) {
        Instant now = clock.instant();
        List<OnCallUnavailability> list = all ? unavailability.findByEndsAtAfterOrderByStartsAtAsc(now)
                : unavailability.findByUserIdAndEndsAtAfterOrderByStartsAtAsc(userId == null ? actor.id() : userId, now);
        return list.stream().map(OnCallService::unavailabilityDto).toList();
    }

    @Transactional
    public UnavailabilityDto addUnavailability(UnavailabilityRequest r, Actor actor) {
        Long userId = r.userId() == null ? actor.id() : r.userId();
        if (!Objects.equals(userId, actor.id()) && !isEditor(actor)) {
            throw ApiException.forbidden("Only on-call editors record unavailability for other people");
        }
        if (!r.endsAt().isAfter(r.startsAt())) {
            throw ApiException.invalidField("endsAt", "endsAt must be after startsAt");
        }
        if (Duration.between(r.startsAt(), r.endsAt()).compareTo(Duration.ofDays(90)) > 0) {
            throw ApiException.invalidField("endsAt", "Unavailability may cover at most 90 days");
        }
        OnCallUnavailability u = new OnCallUnavailability();
        u.setUser(staff(userId, "userId"));
        u.setStartsAt(r.startsAt());
        u.setEndsAt(r.endsAt());
        u.setReason(blankToNull(r.reason()));
        u.setCreatedById(actor.id());
        unavailability.save(u);
        audit.record("ONCALL_UNAVAILABILITY_ADDED", "USER", userId, Map.of("startsAt", r.startsAt().toString(), "endsAt", r.endsAt().toString()));
        return unavailabilityDto(u);
    }

    @Transactional
    public void removeUnavailability(Long id, Actor actor) {
        OnCallUnavailability u = unavailability.findById(id).orElseThrow(() -> ApiException.notFound("Unavailability", id));
        if (!Objects.equals(u.getUser().getId(), actor.id()) && !isEditor(actor)) {
            throw ApiException.forbidden("Only the person or an on-call editor can remove it");
        }
        unavailability.delete(u);
        audit.record("ONCALL_UNAVAILABILITY_REMOVED", "USER", u.getUser().getId(), Map.of("id", id));
    }

    private static UnavailabilityDto unavailabilityDto(OnCallUnavailability u) {
        return new UnavailabilityDto(u.getId(), u.getUser().getId(), u.getUser().getFullName(), u.getStartsAt(), u.getEndsAt(), u.getReason(), u.isDemo(),
                u.getCreatedAt());
    }

    // ------------------------------------------------------------------ handovers

    @Transactional(readOnly = true)
    public List<HandoverDto> handoverNotes(Long scheduleId, Integer size) {
        find(scheduleId);
        int n = size == null ? 20 : Math.max(1, Math.min(size, 100));
        return handovers.findByScheduleIdOrderByCreatedAtDescIdDesc(scheduleId, PageRequest.of(0, n)).stream().map(this::handoverDto).toList();
    }

    /** A handover note for the next person on call (default: the primary of the next shift). */
    @Transactional
    public HandoverDto addHandover(Long scheduleId, HandoverRequest r, Actor actor) {
        OnCallSchedule s = find(scheduleId);
        boolean allowed = isEditor(actor) || participants.existsByScheduleIdAndUserId(scheduleId, actor.id())
                || teamRouting.teamIdsOf(actor.id()).contains(s.getTeam().getId());
        if (!allowed) {
            throw ApiException.forbidden("Only participants of " + s.getName() + ", its team or on-call editors write handover notes");
        }
        Instant now = clock.instant();
        Assignment current = whoAt(s, Layer.PRIMARY, now);
        User to = null;
        if (r.toUserId() != null) {
            to = staff(r.toUserId(), "toUserId");
        } else if (current != null) {
            Assignment next = whoAt(s, Layer.PRIMARY, current.end());
            if (next != null && !Objects.equals(next.userId(), actor.id())) {
                to = users.findById(next.userId()).orElse(null);
            }
        }
        OnCallHandover h = new OnCallHandover();
        h.setScheduleId(scheduleId);
        h.setAuthorId(actor.id());
        h.setAuthorName(actor.name());
        h.setToUser(to);
        h.setShiftStart(r.shiftStart() != null ? r.shiftStart() : current == null ? null : current.start());
        h.setShiftEnd(r.shiftEnd() != null ? r.shiftEnd() : current == null ? null : current.end());
        h.setBody(r.body().trim());
        handovers.save(h);
        if (to != null) {
            notifications.notifyUser(to.getId(), "ONCALL_HANDOVER", "Handover note: " + s.getName(), actor.name() + ": " + truncate(h.getBody(), 300),
                    "/on-call/" + s.getId(), Severity.INFO);
        }
        audit.record("ONCALL_HANDOVER_ADDED", "ONCALL_SCHEDULE", scheduleId, Map.of("to", to == null ? "" : to.getEmail()));
        return handoverDto(h);
    }

    private HandoverDto handoverDto(OnCallHandover h) {
        return new HandoverDto(h.getId(), h.getScheduleId(), h.getAuthorId(), h.getAuthorName(), h.getToUser() == null ? null : h.getToUser().getId(),
                h.getToUser() == null ? null : h.getToUser().getFullName(), h.getShiftStart(), h.getShiftEnd(), h.getBody(), h.isDemo(), h.getCreatedAt());
    }

    // ------------------------------------------------------------------ escalation policies

    @Transactional(readOnly = true)
    public List<PolicyDto> listPolicies() {
        return policies.findAllByOrderByNameAsc().stream().map(this::policyDto).toList();
    }

    @Transactional(readOnly = true)
    public PolicyDto getPolicy(Long id) {
        return policyDto(policies.findWithTeamById(id).orElseThrow(() -> ApiException.notFound("Escalation policy", id)));
    }

    @Transactional
    public PolicyDto createPolicy(PolicyRequest r, Actor actor) {
        if (policies.existsByNameIgnoreCase(r.name().trim())) {
            throw ApiException.conflict("An escalation policy named '" + r.name().trim() + "' already exists");
        }
        EscalationPolicy p = new EscalationPolicy();
        p.setCreatedById(actor.id());
        applyPolicy(p, r);
        policies.save(p);
        audit.record("ONCALL_POLICY_CREATED", "ONCALL_POLICY", p.getId(), Map.of("name", p.getName(), "levels", p.getLevels().size()));
        return policyDto(p);
    }

    @Transactional
    public PolicyDto updatePolicy(Long id, PolicyRequest r) {
        EscalationPolicy p = policies.findWithTeamById(id).orElseThrow(() -> ApiException.notFound("Escalation policy", id));
        if (policies.existsByNameIgnoreCaseAndIdNot(r.name().trim(), id)) {
            throw ApiException.conflict("An escalation policy named '" + r.name().trim() + "' already exists");
        }
        applyPolicy(p, r);
        audit.record("ONCALL_POLICY_UPDATED", "ONCALL_POLICY", p.getId(), Map.of("name", p.getName(), "levels", p.getLevels().size(), "active", p.isActive()));
        return policyDto(p);
    }

    private void applyPolicy(EscalationPolicy p, PolicyRequest r) {
        p.setName(r.name().trim());
        p.setDescription(blankToNull(r.description()));
        p.setTeam(r.teamId() == null ? null : teams.findById(r.teamId())
                .orElseThrow(() -> ApiException.invalidField("teamId", "Team " + r.teamId() + " does not exist")));
        p.setMinSeverity(r.minSeverity() == null ? com.nexus360.alert.AlertSeverity.MAJOR : r.minSeverity());
        List<Level> levels = new ArrayList<>();
        for (int i = 0; i < r.levels().size(); i++) {
            OnCallDtos.LevelRequest l = r.levels().get(i);
            Long userId = null;
            if (l.target() == Target.USER) {
                if (l.userId() == null) {
                    throw ApiException.invalidField("levels[" + i + "].userId", "A USER level needs userId");
                }
                userId = staff(l.userId(), "levels[" + i + "].userId").getId();
            }
            levels.add(new Level(l.delayMinutes(), l.target(), userId));
        }
        p.setLevels(levels);
        p.setActive(r.active() == null || r.active());
    }

    PolicyDto policyDto(EscalationPolicy p) {
        Set<Long> ids = p.getLevels().stream().map(Level::userId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = ids.isEmpty() ? Map.of() : users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, User::getFullName));
        List<LevelDto> levels = new ArrayList<>();
        int cumulative = 0;
        for (int i = 0; i < p.getLevels().size(); i++) {
            Level l = p.getLevels().get(i);
            cumulative += l.delayMinutes();
            levels.add(new LevelDto(i + 1, l.delayMinutes(), cumulative, l.target(), l.userId(), l.userId() == null ? null : names.get(l.userId()),
                    label(l.target(), l.userId() == null ? null : names.get(l.userId()))));
        }
        return new PolicyDto(p.getId(), p.getName(), p.getDescription(), p.getTeam() == null ? null : p.getTeam().getId(),
                p.getTeam() == null ? null : p.getTeam().getName(), p.getMinSeverity(), levels, p.isActive(),
                p.getId() == null ? 0 : schedules.countByEscalationPolicyId(p.getId()), p.isDemo(), p.getCreatedAt(), p.getUpdatedAt());
    }

    static String label(Target target, String userName) {
        return switch (target) {
            case PRIMARY -> "Primary on call";
            case SECONDARY -> "Secondary on call";
            case MANAGER -> "Team lead and department manager";
            case USER -> userName == null ? "Named person" : userName;
        };
    }

    // ------------------------------------------------------------------ pages

    @Transactional(readOnly = true)
    public List<PageDto> pages(Long scheduleId, Long alertId, Integer size) {
        int n = size == null ? 50 : Math.max(1, Math.min(size, 200));
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where true");
        if (scheduleId != null) {
            where.append(" and p.schedule_id = ?");
            args.add(scheduleId);
        }
        if (alertId != null) {
            where.append(" and p.alert_id = ?");
            args.add(alertId);
        }
        args.add(n);
        return jdbc.query("select p.id, p.alert_id, a.number, a.title, a.severity, a.status, p.level, p.target, p.user_id, u.full_name, "
                + "p.schedule_id, s.name as schedule_name, p.paged_at, a.acknowledged_at, p.is_demo from oncall_pages p join alerts a on a.id = p.alert_id "
                + "join users u on u.id = p.user_id left join oncall_schedules s on s.id = p.schedule_id" + where
                + " order by p.paged_at desc, p.id desc limit ?", (rs, i) -> {
            Instant paged = rs.getTimestamp("paged_at").toInstant();
            Timestamp ack = rs.getTimestamp("acknowledged_at");
            Double minutes = ack == null || ack.toInstant().isBefore(paged) ? null
                    : Math.round(Duration.between(paged, ack.toInstant()).getSeconds() / 6.0) / 10.0;
            return new PageDto(rs.getLong("id"), rs.getLong("alert_id"), rs.getString("number"), rs.getString("title"), rs.getString("severity"),
                    rs.getString("status"), rs.getInt("level"), Target.valueOf(rs.getString("target")), rs.getLong("user_id"), rs.getString("full_name"),
                    (Long) rs.getObject("schedule_id"), rs.getString("schedule_name"), paged, ack == null ? null : ack.toInstant(), minutes,
                    rs.getBoolean("is_demo"));
        }, args.toArray());
    }

    // ------------------------------------------------------------------ performance

    /**
     * On-call performance per schedule for pages sent in [from, to) (schedules of one department when given): {@code alertsPaged}
     * alerts that paged somebody, {@code pages} pages sent, {@code acknowledged} of those alerts acknowledged, {@code ackMinutesSum}
     * / {@code ackSamples} for the mean time from the first page to the acknowledgement, {@code escalated} alerts that reached
     * level 2 or higher.
     */
    public record PerformanceRow(Long scheduleId, String scheduleName, Long teamId, String teamName, long alertsPaged, long pages, long acknowledged,
                                 double ackMinutesSum, long ackSamples, long escalated) {}

    @Transactional(readOnly = true)
    public List<PerformanceRow> performance(Instant from, Instant to, Long departmentId) {
        return jdbc.query("with first_pages as (select p.alert_id, min(p.paged_at) as first_paged, max(p.level) as max_level, count(*) as pages, "
                + "min(p.schedule_id) as schedule_id from oncall_pages p left join oncall_schedules s on s.id = p.schedule_id "
                + "left join teams t on t.id = s.team_id where p.paged_at >= ? and p.paged_at < ? and (cast(? as bigint) is null or t.department_id = ?) "
                + "group by p.alert_id) "
                + "select fp.schedule_id, s.name as schedule_name, t.id as team_id, t.name as team_name, count(*) as alerts, sum(fp.pages) as pages, "
                + "count(*) filter (where a.acknowledged_at is not null) as acknowledged, "
                + "coalesce(sum(extract(epoch from (a.acknowledged_at - fp.first_paged)) / 60) filter (where a.acknowledged_at >= fp.first_paged), 0) as ack_sum, "
                + "count(*) filter (where a.acknowledged_at >= fp.first_paged) as ack_n, count(*) filter (where fp.max_level >= 2) as escalated "
                + "from first_pages fp join alerts a on a.id = fp.alert_id left join oncall_schedules s on s.id = fp.schedule_id "
                + "left join teams t on t.id = s.team_id where not a.simulated group by fp.schedule_id, s.name, t.id, t.name order by alerts desc, schedule_name",
                (rs, n) -> new PerformanceRow((Long) rs.getObject("schedule_id"), rs.getString("schedule_name"), (Long) rs.getObject("team_id"),
                        rs.getString("team_name"), rs.getLong("alerts"), rs.getLong("pages"), rs.getLong("acknowledged"), rs.getDouble("ack_sum"),
                        rs.getLong("ack_n"), rs.getLong("escalated")),
                Timestamp.from(from), Timestamp.from(to), departmentId, departmentId);
    }

    // ------------------------------------------------------------------ helpers

    OnCallSchedule find(Long id) {
        return schedules.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("On-call schedule", id));
    }

    static ZoneId zone(String id) {
        try {
            return ZoneId.of(id == null ? "" : id.trim());
        } catch (DateTimeException e) {
            throw ApiException.invalidField("timeZone", "Unknown time zone '" + id + "' (use an IANA name such as Asia/Kolkata)");
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }

    /** Users by id (for labels). */
    Map<Long, User> usersById(Set<Long> ids) {
        Map<Long, User> map = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            users.findAllById(ids).forEach(u -> map.put(u.getId(), u));
        }
        return map;
    }

    static Map<String, Object> details(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}

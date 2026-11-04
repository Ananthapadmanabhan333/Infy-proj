package com.nexus360.sla;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.Priority;
import com.nexus360.sla.SlaDtos.CalendarDto;
import com.nexus360.sla.SlaDtos.CalendarRequest;
import com.nexus360.sla.SlaDtos.ComplianceTrendPoint;
import com.nexus360.sla.SlaDtos.DepartmentCompliance;
import com.nexus360.sla.SlaDtos.SlaKpis;
import com.nexus360.sla.SlaDtos.HolidayDto;
import com.nexus360.sla.SlaDtos.HolidayRequest;
import com.nexus360.sla.SlaDtos.PriorityCompliance;
import com.nexus360.sla.SlaDtos.ServiceCompliance;
import com.nexus360.sla.SlaDtos.SlaOverview;
import com.nexus360.sla.SlaDtos.SlaPolicyDto;
import com.nexus360.sla.SlaDtos.SlaPolicyRequest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SlaAdminService {

    private final SlaPolicyRepository policies;
    private final BusinessHoursCalendarRepository calendars;
    private final IncidentSlaRepository slas;
    private final IncidentMapper mapper;
    private final SlaEngine engine;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    // ------------------------------------------------------------------ policies

    @Transactional(readOnly = true)
    public List<SlaPolicyDto> policies() {
        return policies.findAllByOrderByPriorityAscNameAsc().stream()
                .sorted((a, b) -> a.getPriority().compareTo(b.getPriority()))
                .map(SlaAdminService::dto).toList();
    }

    @Transactional
    public SlaPolicyDto savePolicy(Long id, SlaPolicyRequest r) {
        SlaPolicy p = id == null ? new SlaPolicy() : policies.findById(id).orElseThrow(() -> ApiException.notFound("SLA policy", id));
        if (r.resolutionMinutes() < r.responseMinutes()) {
            throw ApiException.badRequest("Resolution target must not be shorter than the response target");
        }
        BusinessHoursCalendar calendar = null;
        if (r.businessHoursOnly()) {
            if (r.calendarId() == null) {
                throw ApiException.badRequest("A business-hours policy needs a calendar");
            }
            calendar = calendars.findById(r.calendarId()).orElseThrow(() -> ApiException.badRequest("Calendar does not exist"));
        }
        if (r.active()) {
            // Only one active policy per priority (enforced by a partial unique index as well).
            policies.findAllByOrderByPriorityAscNameAsc().stream()
                    .filter(o -> o.isActive() && o.getPriority() == r.priority() && !o.getId().equals(id))
                    .forEach(o -> o.setActive(false));
            policies.flush();
        }
        p.setName(r.name().trim());
        p.setPriority(r.priority());
        p.setResponseMinutes(r.responseMinutes());
        p.setResolutionMinutes(r.resolutionMinutes());
        p.setBusinessHoursOnly(r.businessHoursOnly());
        p.setCalendar(calendar);
        p.setAtRiskPercent(r.atRiskPercent());
        p.setActive(r.active());
        p.setDescription(r.description());
        policies.save(p);
        audit.record(id == null ? "SLA_POLICY_CREATED" : "SLA_POLICY_UPDATED", "SLA_POLICY", p.getId(),
                Map.of("priority", p.getPriority(), "response", p.getResponseMinutes(), "resolution", p.getResolutionMinutes()));
        return dto(p);
    }

    // ------------------------------------------------------------------ calendars

    @Transactional(readOnly = true)
    public List<CalendarDto> calendars() {
        return calendars.findAllByOrderByNameAsc().stream().map(SlaAdminService::dto).toList();
    }

    @Transactional
    public CalendarDto updateCalendar(Long id, CalendarRequest r) {
        BusinessHoursCalendar c = calendars.findWithHolidaysById(id).orElseThrow(() -> ApiException.notFound("Calendar", id));
        LocalTime start;
        LocalTime end;
        try {
            ZoneId.of(r.timezone());
            start = LocalTime.parse(r.startTime());
            end = LocalTime.parse(r.endTime());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Invalid timezone or time");
        }
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("End time must be after start time");
        }
        c.setName(r.name().trim());
        c.setTimezone(r.timezone());
        c.setWorkDaySet(r.workDays());
        c.setStartTime(start);
        c.setEndTime(end);
        audit.record("SLA_CALENDAR_UPDATED", "SLA_CALENDAR", c.getId(), Map.of("timezone", r.timezone(), "hours", r.startTime() + "-" + r.endTime()));
        return dto(c);
    }

    @Transactional
    public CalendarDto addHoliday(Long calendarId, HolidayRequest r) {
        BusinessHoursCalendar c = calendars.findWithHolidaysById(calendarId).orElseThrow(() -> ApiException.notFound("Calendar", calendarId));
        if (c.getHolidays().stream().anyMatch(h -> h.getHolidayDate().equals(r.date()))) {
            throw ApiException.conflict("A holiday already exists on " + r.date());
        }
        CalendarHoliday h = new CalendarHoliday();
        h.setCalendar(c);
        h.setHolidayDate(r.date());
        h.setName(r.name().trim());
        c.getHolidays().add(h);
        calendars.saveAndFlush(c);
        audit.record("SLA_HOLIDAY_ADDED", "SLA_CALENDAR", c.getId(), Map.of("date", r.date().toString(), "name", r.name()));
        return dto(c);
    }

    @Transactional
    public CalendarDto removeHoliday(Long calendarId, Long holidayId) {
        BusinessHoursCalendar c = calendars.findWithHolidaysById(calendarId).orElseThrow(() -> ApiException.notFound("Calendar", calendarId));
        boolean removed = c.getHolidays().removeIf(h -> h.getId().equals(holidayId));
        if (!removed) {
            throw ApiException.notFound("Holiday", holidayId);
        }
        audit.record("SLA_HOLIDAY_REMOVED", "SLA_CALENDAR", c.getId(), Map.of("holiday", holidayId));
        return dto(c);
    }

    // ------------------------------------------------------------------ monitoring

    @Transactional(readOnly = true)
    public SlaOverview overview(int days) {
        return overview(days, false);
    }

    /** SLA monitor figures; clocks of simulated incidents (simulation mode) are excluded unless {@code includeSimulated}. */
    @Transactional(readOnly = true)
    public SlaOverview overview(int days, boolean includeSimulated) {
        int window = Math.max(1, Math.min(days, 365));
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofDays(window));
        String sim = includeSimulated ? "" : " and not i.simulated";
        List<IncidentSla> completed = slas.findCompletedSince(from).stream().filter(s -> includeSimulated || !s.getIncident().isSimulated()).toList();
        long met = completed.stream().filter(s -> s.getOutcome() == IncidentSla.Outcome.MET).count();
        long missed = completed.size() - met;
        long responded = completed.stream().filter(s -> s.getRespondedAt() != null).count();
        long respondedOnTime = completed.stream().filter(s -> s.getRespondedAt() != null && !s.isResponseBreached()).count();

        Map<Priority, long[]> byPriority = new EnumMap<>(Priority.class);
        for (Priority p : Priority.values()) {
            byPriority.put(p, new long[3]);
        }
        Map<Long, ServiceAgg> byService = new LinkedHashMap<>();
        for (IncidentSla s : completed) {
            byPriority.get(s.getPriority())[s.getOutcome() == IncidentSla.Outcome.MET ? 0 : 1]++;
            Incident i = s.getIncident();
            if (i.getService() != null) {
                ServiceAgg agg = byService.computeIfAbsent(i.getService().getId(), k -> new ServiceAgg(i.getService().getName()));
                if (s.getOutcome() == IncidentSla.Outcome.MET) {
                    agg.met++;
                } else {
                    agg.missed++;
                }
            }
        }
        jdbc.query("""
                select s.priority, i.service_id, bs.name, s.health, s.clock_status
                from incident_slas s join incidents i on i.id = s.incident_id left join business_services bs on bs.id = i.service_id
                where s.clock_status <> 'STOPPED'""" + sim, rs -> {
            Priority p = Priority.valueOf(rs.getString(1));
            byPriority.get(p)[2]++;
            long serviceId = rs.getLong(2);
            if (!rs.wasNull()) {
                String serviceName = rs.getString(3);
                ServiceAgg agg = byService.computeIfAbsent(serviceId, k -> new ServiceAgg(serviceName));
                String health = rs.getString(4);
                if ("AT_RISK".equals(health)) {
                    agg.atRisk++;
                } else if ("BREACHED".equals(health)) {
                    agg.breached++;
                }
            }
        });

        List<PriorityCompliance> priorityRows = new ArrayList<>();
        byPriority.forEach((p, v) -> priorityRows.add(new PriorityCompliance(p, v[0], v[1], v[2], pct(v[0], v[0] + v[1]))));
        List<ServiceCompliance> serviceRows = byService.entrySet().stream()
                .map(e -> new ServiceCompliance(e.getKey(), e.getValue().name, e.getValue().met, e.getValue().missed, e.getValue().atRisk,
                        e.getValue().breached, pct(e.getValue().met, e.getValue().met + e.getValue().missed)))
                .sorted((a, b) -> a.serviceName().compareToIgnoreCase(b.serviceName()))
                .toList();

        Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
        LocalDate first = LocalDate.ofInstant(from, ZoneOffset.UTC).plusDays(1);
        for (LocalDate d = first; !d.isAfter(LocalDate.ofInstant(now, ZoneOffset.UTC)); d = d.plusDays(1)) {
            perDay.put(d, new long[2]);
        }
        for (IncidentSla s : completed) {
            long[] bucket = perDay.get(LocalDate.ofInstant(s.getCompletedAt(), ZoneOffset.UTC));
            if (bucket != null) {
                bucket[s.getOutcome() == IncidentSla.Outcome.MET ? 0 : 1]++;
            }
        }
        List<ComplianceTrendPoint> trend = perDay.entrySet().stream()
                .map(e -> new ComplianceTrendPoint(e.getKey().toString(), e.getValue()[0], e.getValue()[1],
                        pct(e.getValue()[0], e.getValue()[0] + e.getValue()[1])))
                .toList();

        Map<String, Object> live = jdbc.queryForMap("""
                select count(*) filter (where s.clock_status <> 'STOPPED') as active,
                       count(*) filter (where s.clock_status = 'PAUSED') as paused,
                       count(*) filter (where s.clock_status <> 'STOPPED' and s.health = 'AT_RISK') as at_risk,
                       count(*) filter (where s.clock_status <> 'STOPPED' and s.health = 'BREACHED') as breached
                from incident_slas s join incidents i on i.id = s.incident_id where true""" + sim);
        long active = ((Number) live.get("active")).longValue();
        long paused = ((Number) live.get("paused")).longValue();
        long atRisk = ((Number) live.get("at_risk")).longValue();
        long breached = ((Number) live.get("breached")).longValue();
        long onTrack = Math.max(0, active - paused - atRisk - breached);

        // Breaches in the period: clocks flagged by the evaluator, plus clocks that completed late without being flagged.
        java.sql.Timestamp fromTs = java.sql.Timestamp.from(from);
        Long breachesInPeriod = jdbc.queryForObject("""
                select count(*) from incident_slas s join incidents i on i.id = s.incident_id
                where (s.breached_at >= ? or (s.breached_at is null and s.outcome = 'MISSED' and s.completed_at >= ?))""" + sim,
                Long.class, fromTs, fromTs);
        Double avgMinutes = jdbc.queryForObject("""
                select round(avg(s.consumed_seconds) / 60.0, 1) from incident_slas s join incidents i on i.id = s.incident_id
                where s.completed_at >= ? and s.outcome in ('MET', 'MISSED')""" + sim, Double.class, fromTs);
        SlaKpis kpis = new SlaKpis(pct(met, met + missed), breachesInPeriod == null ? 0 : breachesInPeriod, atRisk, avgMinutes);
        List<DepartmentCompliance> byDepartment = jdbc.query("""
                select coalesce(d.name, 'Unassigned') as name,
                       count(*) filter (where s.completed_at >= ? and s.outcome = 'MET') as met,
                       count(*) filter (where s.completed_at >= ? and s.outcome = 'MISSED') as missed,
                       count(*) filter (where s.clock_status <> 'STOPPED' and s.health = 'AT_RISK') as at_risk
                from incident_slas s join incidents i on i.id = s.incident_id left join departments d on d.id = i.department_id
                where ((s.completed_at >= ? and s.outcome in ('MET', 'MISSED')) or s.clock_status <> 'STOPPED')""" + sim + " " + """
                 group by 1 order by 1""",
                (rs, n) -> new DepartmentCompliance(rs.getString("name"), rs.getLong("met"), rs.getLong("missed"), rs.getLong("at_risk"),
                        pct(rs.getLong("met"), rs.getLong("met") + rs.getLong("missed"))), fromTs, fromTs, fromTs);
        return new SlaOverview(window, active, onTrack, atRisk, breached, paused, met, missed, pct(met, met + missed),
                pct(respondedOnTime, responded), priorityRows, serviceRows, trend, kpis, byDepartment);
    }

    @Transactional(readOnly = true)
    public PageResponse<IncidentSummary> active(String health, Integer page, Integer size) {
        PageRequest pageable = PageRequest.of(page == null ? 0 : Math.max(0, page), size == null ? 20 : Math.min(Math.max(size, 1), 100));
        Page<IncidentSla> result;
        if (health == null || health.isBlank()) {
            result = slas.findAllActive(pageable);
        } else if ("PAUSED".equalsIgnoreCase(health)) {
            result = slas.findPaused(pageable);
        } else {
            result = slas.findByClockAndHealth(IncidentSla.ClockStatus.RUNNING,
                    IncidentSla.Health.valueOf(health.toUpperCase(Locale.ROOT)), pageable);
        }
        List<Incident> incidents = result.getContent().stream().map(IncidentSla::getIncident).toList();
        return PageResponse.of(result, mapper.summaries(incidents));
    }

    @Transactional
    public SlaEngine.EvaluationResult evaluateNow() {
        SlaEngine.EvaluationResult result = engine.evaluate();
        audit.record("SLA_EVALUATED_MANUALLY", "SLA", null, Map.of("evaluated", result.evaluated(), "atRisk", result.atRisk(),
                "breached", result.breached(), "escalated", result.escalated()));
        return result;
    }

    // ------------------------------------------------------------------ mapping

    private static final class ServiceAgg {
        final String name;
        long met;
        long missed;
        long atRisk;
        long breached;

        ServiceAgg(String name) {
            this.name = name;
        }
    }

    private static Double pct(long part, long whole) {
        return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
    }

    static SlaPolicyDto dto(SlaPolicy p) {
        return new SlaPolicyDto(p.getId(), p.getName(), p.getPriority(), p.getResponseMinutes(), p.getResolutionMinutes(),
                p.isBusinessHoursOnly(), p.getCalendar() == null ? null : p.getCalendar().getId(),
                p.getCalendar() == null ? null : p.getCalendar().getName(), p.getAtRiskPercent(), p.isActive(), p.getDescription());
    }

    static CalendarDto dto(BusinessHoursCalendar c) {
        return new CalendarDto(c.getId(), c.getName(), c.getTimezone(), c.workDaySet().stream().sorted().toList(),
                c.getStartTime().toString(), c.getEndTime().toString(), c.isDefaultCalendar(),
                c.getHolidays().stream().map(h -> new HolidayDto(h.getId(), h.getHolidayDate(), h.getName())).toList());
    }
}

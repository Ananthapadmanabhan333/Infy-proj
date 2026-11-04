package com.nexus360.analytics;

import com.nexus360.ai.AiDtos.HourCount;
import com.nexus360.analytics.AnalyticsDtos.AgentRow;
import com.nexus360.analytics.AnalyticsDtos.AgingBucket;
import com.nexus360.analytics.AnalyticsDtos.CategoryRow;
import com.nexus360.analytics.AnalyticsDtos.Counts;
import com.nexus360.analytics.AnalyticsDtos.Dashboard;
import com.nexus360.analytics.AnalyticsDtos.Deltas;
import com.nexus360.analytics.AnalyticsDtos.DepartmentRow;
import com.nexus360.analytics.AnalyticsDtos.Forecast;
import com.nexus360.analytics.AnalyticsDtos.ForecastPoint;
import com.nexus360.analytics.AnalyticsDtos.HistoryPoint;
import com.nexus360.analytics.AnalyticsDtos.Kpis;
import com.nexus360.analytics.AnalyticsDtos.Overview;
import com.nexus360.analytics.AnalyticsDtos.PriorityCount;
import com.nexus360.analytics.AnalyticsDtos.PriorityRow;
import com.nexus360.analytics.AnalyticsDtos.RecurringRow;
import com.nexus360.analytics.AnalyticsDtos.ServiceForecast;
import com.nexus360.analytics.AnalyticsDtos.ServiceRow;
import com.nexus360.analytics.AnalyticsDtos.ServiceVolumePoint;
import com.nexus360.analytics.AnalyticsDtos.TrendPoint;
import com.nexus360.approval.ApprovalService;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceDto;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceStatus;
import com.nexus360.cmdb.ServiceHealthService;
import com.nexus360.common.ApiException;
import com.nexus360.common.Csv;
import com.nexus360.incident.Category;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.incident.Priority;
import com.nexus360.knowledge.KnowledgeService;
import com.nexus360.notification.NotificationRepository;
import com.nexus360.security.Actor;
import com.nexus360.servicedesk.ServiceDeskService;
import com.nexus360.servicedesk.ServiceRequestRepository;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.workflow.WorkflowExecutionRepository;
import com.nexus360.workflow.WorkflowRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operational analytics computed from live records. Incident metrics count top-level incidents only
 * (standalone incidents and parents); duplicate child reports are represented by their parent, and cancelled
 * incidents (closed with resolution code CANCELLED) never count as resolved.
 *
 * <p>Every report takes a {@link Period}: either the last {@code days} (rolling) or an inclusive {@code from}–{@code to}
 * date range in the default business calendar's time zone, optionally narrowed to one assignment department.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsService {

    public static final String FORECAST_METHOD = "Statistical forecast: least-squares linear trend on daily incident counts (95% band)";
    private static final String ACTIVE = IncidentStatus.activeSqlList();
    private static final String NOT_CANCELLED = "coalesce(i.resolution_code, '') <> 'CANCELLED'";
    private static final int MAX_RANGE_DAYS = 366;

    /** Reporting window [from, to) with its inclusive calendar dates, and an optional department filter. */
    public record Period(Instant from, Instant to, LocalDate fromDate, LocalDate toDate, int days, Long departmentId, boolean includeSimulated) {

        /** Simulated incidents, alerts and deployments are excluded (Release 4 ground rule: analytics exclude simulated data by default). */
        public Period(Instant from, Instant to, LocalDate fromDate, LocalDate toDate, int days, Long departmentId) {
            this(from, to, fromDate, toDate, days, departmentId, false);
        }

        public Period withSimulated(boolean include) {
            return new Period(from, to, fromDate, toDate, days, departmentId, include);
        }

        Instant prevFrom() {
            return from.minus(Duration.between(from, to));
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final IncidentRepository incidents;
    private final IncidentMapper mapper;
    private final ServiceHealthService serviceHealth;
    private final WorkflowRepository workflows;
    private final WorkflowExecutionRepository executions;
    private final KnowledgeService knowledge;
    private final ApprovalService approvals;
    private final NotificationRepository notifications;
    private final ServiceRequestRepository serviceRequests;
    private final DepartmentRepository departments;
    private final AnalyticsPdf pdf;
    private final AuditService audit;
    private final OperationsMetricsService operations;
    private final Clock clock;

    // ------------------------------------------------------------------ periods

    /** Rolling window: the last {@code days} days up to now. */
    public Period period(int days) {
        return period(null, null, days, null);
    }

    /**
     * {@code from}/{@code to} are inclusive dates; when only one is given the other is derived from {@code days}
     * (default 30). Without dates the window is the last {@code days} days up to now.
     */
    public Period period(LocalDate from, LocalDate to, Integer days, Long departmentId, boolean includeSimulated) {
        return period(from, to, days, departmentId).withSimulated(includeSimulated);
    }

    public Period period(LocalDate from, LocalDate to, Integer days, Long departmentId) {
        if (departmentId != null && !departments.existsById(departmentId)) {
            throw ApiException.badRequest("Department " + departmentId + " does not exist");
        }
        ZoneId zone = zone();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        int window = clampDays(days == null ? 30 : days);
        if (from == null && to == null) {
            return new Period(now.minus(Duration.ofDays(window)), now.plus(Duration.ofMinutes(1)), today.minusDays(window - 1L), today,
                    window, departmentId);
        }
        LocalDate end = to != null ? to : from.plusDays(window - 1L);
        LocalDate start = from != null ? from : end.minusDays(window - 1L);
        if (start.isAfter(end)) {
            throw ApiException.badRequest("'from' must not be after 'to'");
        }
        long length = ChronoUnit.DAYS.between(start, end) + 1;
        if (length > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("The date range may cover at most " + MAX_RANGE_DAYS + " days");
        }
        return new Period(start.atStartOfDay(zone).toInstant(), end.plusDays(1).atStartOfDay(zone).toInstant(), start, end, (int) length,
                departmentId);
    }

    // ------------------------------------------------------------------ dashboard

    public Dashboard dashboard(int days, Actor actor) {
        return dashboard(period(days), actor);
    }

    public Dashboard dashboard(Period p, Actor actor) {
        Instant now = clock.instant();
        List<TrendPoint> trend = trend(p);
        Forecast forecast = forecast(Math.max(p.days(), 28), 7, p.departmentId(), p.includeSimulated());
        List<ServiceDto> health = serviceHealth.list().stream()
                .sorted(Comparator.comparingInt((ServiceDto s) -> statusRank(s.status())).thenComparing(ServiceDto::tier).thenComparing(ServiceDto::name))
                .limit(6).toList();
        var active = mapper.summaries(incidents.findActiveByPriority(IncidentStatus.ACTIVE, PageRequest.of(0, 6)));
        var queue = actor.isStaff()
                ? mapper.summaries(incidents.findQueueForAssignee(actor.id(), IncidentStatus.ACTIVE, PageRequest.of(0, 8)))
                : actor.role() == Role.EMPLOYEE ? mapper.summaries(incidents.findTop10ByRequesterIdOrderByCreatedAtDesc(actor.id()))
                : List.<IncidentSummary>of();
        var kb = knowledge.stats();
        Counts counts = new Counts(workflows.countByActiveTrue(), executions.countByStartedAtAfter(now.minus(Duration.ofHours(24))),
                kb.published(), kb.resolutionRate(), approvals.pendingCount(actor),
                actor.id() == null ? 0 : notifications.countByUserIdAndReadAtIsNull(actor.id()),
                actor.role() == Role.EMPLOYEE ? serviceRequests.countByRequesterIdAndStatusIn(actor.id(), ServiceDeskService.OPEN_STATUSES)
                        : serviceRequests.countByStatusIn(ServiceDeskService.OPEN_STATUSES));
        return new Dashboard(p.days(), kpis(p), trend, forecast.forecast(), FORECAST_METHOD, distribution(p), health, active, queue, counts,
                p.fromDate().toString(), p.toDate().toString(), p.departmentId(), actor.role().isReader() ? operations.commandCenter(p) : null);
    }

    public Kpis kpis(int days, Instant now) {
        Period rolling = new Period(now.minus(Duration.ofDays(days)), now.plus(Duration.ofMinutes(1)), null, null, days, null);
        return kpis(rolling);
    }

    public Kpis kpis(Period p) {
        MapSqlParameterSource params = params(p);
        String dept = scope(p, "i");
        Map<String, Object> r = jdbc.queryForMap("select "
                + "count(*) filter (where i.parent_id is null and i.created_at < :to) as total, "
                + "count(*) filter (where i.parent_id is null and i.status in " + ACTIVE + ") as open, "
                + "count(*) filter (where i.parent_id is null and i.priority = 'CRITICAL' and i.status in " + ACTIVE + ") as critical_open, "
                + "count(*) filter (where i.parent_id is null and i.created_at >= :from and i.created_at < :to) as created, "
                + "count(*) filter (where i.parent_id is null and i.created_at >= :prevFrom and i.created_at < :from) as created_prev, "
                + "count(*) filter (where i.parent_id is null and i.priority = 'CRITICAL' and i.created_at >= :from and i.created_at < :to) as critical_created, "
                + "count(*) filter (where i.parent_id is null and i.priority = 'CRITICAL' and i.created_at >= :prevFrom and i.created_at < :from) as critical_prev, "
                + "count(*) filter (where i.parent_id is null and i.resolved_at >= :from and i.resolved_at < :to and " + NOT_CANCELLED + ") as resolved, "
                + "count(*) filter (where i.parent_id is null and i.created_at >= :from and i.created_at < :to "
                + "  and i.status in ('RESOLVED','CLOSED') and " + NOT_CANCELLED + ") as created_resolved, "
                + "avg(extract(epoch from (i.resolved_at - i.created_at)) / 60) filter (where i.parent_id is null and i.resolved_at >= :from "
                + "  and i.resolved_at < :to and " + NOT_CANCELLED + ") as mttr, "
                + "avg(extract(epoch from (i.resolved_at - i.created_at)) / 60) filter (where i.parent_id is null and i.resolved_at >= :prevFrom "
                + "  and i.resolved_at < :from and " + NOT_CANCELLED + ") as mttr_prev, "
                + "avg(extract(epoch from (i.first_response_at - i.created_at)) / 60) filter (where i.parent_id is null "
                + "  and i.first_response_at is not null and i.created_at >= :from and i.created_at < :to) as mtta "
                + "from incidents i where true" + dept, params);
        Map<String, Object> s = jdbc.queryForMap("select "
                + "count(*) filter (where s.completed_at >= :from and s.completed_at < :to and s.outcome = 'MET') as met, "
                + "count(*) filter (where s.completed_at >= :from and s.completed_at < :to and s.outcome in ('MET','MISSED')) as done, "
                + "count(*) filter (where s.completed_at >= :prevFrom and s.completed_at < :from and s.outcome = 'MET') as met_prev, "
                + "count(*) filter (where s.completed_at >= :prevFrom and s.completed_at < :from and s.outcome in ('MET','MISSED')) as done_prev "
                + "from incident_slas s join incidents i on i.id = s.incident_id where true" + dept, params);
        // First-call resolution: resolved with at most one distinct assignee in the history and never reopened.
        Map<String, Object> f = jdbc.queryForMap("select count(*) as resolved, coalesce(sum(case when "
                + "(select count(distinct h.new_value) from incident_history h where h.incident_id = i.id and h.field = 'assignee' "
                + "  and h.new_value is not null) <= 1 "
                + "and not exists (select 1 from incident_history h where h.incident_id = i.id and h.field = 'status' "
                + "  and (h.new_value = 'REOPENED' or (h.old_value = 'RESOLVED' and h.new_value = 'IN_PROGRESS'))) "
                + "then 1 else 0 end), 0) as fcr "
                + "from incidents i where i.parent_id is null and i.resolved_at >= :from and i.resolved_at < :to and " + NOT_CANCELLED + dept, params);
        Double sla = pct(num(s.get("met")), num(s.get("done")));
        Double slaPrev = pct(num(s.get("met_prev")), num(s.get("done_prev")));
        Double mttr = dbl(r.get("mttr"));
        Double mttrPrev = dbl(r.get("mttr_prev"));
        long created = num(r.get("created"));
        long createdPrev = num(r.get("created_prev"));
        Deltas deltas = new Deltas(createdPrev == 0 ? null : round1((created - createdPrev) * 100.0 / createdPrev),
                num(r.get("critical_created")) - num(r.get("critical_prev")),
                sla == null || slaPrev == null ? null : round1(sla - slaPrev),
                mttr == null || mttrPrev == null || mttrPrev == 0 ? null : round1((mttr - mttrPrev) * 100.0 / mttrPrev));
        return new Kpis(num(r.get("total")), num(r.get("open")), num(r.get("critical_open")), created, num(r.get("resolved")), sla,
                mttr == null ? null : round1(mttr), dbl(r.get("mtta")) == null ? null : round1(dbl(r.get("mtta"))), deltas,
                pct(num(r.get("created_resolved")), created), pct(num(f.get("fcr")), num(f.get("resolved"))));
    }

    List<TrendPoint> trend(Period p) {
        ZoneId zone = zone();
        MapSqlParameterSource params = params(p).addValue("tz", zone.getId());
        Map<String, long[]> series = new LinkedHashMap<>();
        for (LocalDate d = p.fromDate(); !d.isAfter(p.toDate()); d = d.plusDays(1)) {
            series.put(d.toString(), new long[2]);
        }
        MapSqlParameterSource dayParams = params.addValue("dayFrom", at(p.fromDate().atStartOfDay(zone).toInstant()));
        String dept = scope(p, "i");
        jdbc.query("select to_char(i.created_at at time zone :tz, 'YYYY-MM-DD') as d, count(*) as c from incidents i "
                + "where i.parent_id is null and i.created_at >= :dayFrom and i.created_at < :to" + dept + " group by 1", dayParams, rs -> {
            long[] v = series.get(rs.getString("d"));
            if (v != null) {
                v[0] = rs.getLong("c");
            }
        });
        jdbc.query("select to_char(i.resolved_at at time zone :tz, 'YYYY-MM-DD') as d, count(*) as c from incidents i "
                + "where i.parent_id is null and i.resolved_at >= :dayFrom and i.resolved_at < :to and " + NOT_CANCELLED + dept
                + " group by 1", dayParams, rs -> {
            long[] v = series.get(rs.getString("d"));
            if (v != null) {
                v[1] = rs.getLong("c");
            }
        });
        return series.entrySet().stream().map(e -> new TrendPoint(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
    }

    private List<PriorityCount> distribution(Period p) {
        Map<Priority, Long> counts = new EnumMap<>(Priority.class);
        for (Priority pr : Priority.values()) {
            counts.put(pr, 0L);
        }
        jdbc.query("select i.priority, count(*) as c from incidents i where i.parent_id is null and i.created_at >= :from "
                + "and i.created_at < :to" + scope(p, "i") + " group by i.priority", params(p),
                rs -> {
                    counts.put(Priority.valueOf(rs.getString("priority")), rs.getLong("c"));
                });
        return counts.entrySet().stream().map(e -> new PriorityCount(e.getKey(), e.getValue())).toList();
    }

    /** Incidents created per hour of day (0–23, default calendar time zone) over the last {@code days} days. */
    public List<HourCount> hourlyCreated(int days) {
        long[] counts = new long[24];
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("from", at(clock.instant().minus(Duration.ofDays(clampDays(days)))))
                .addValue("tz", zone().getId());
        jdbc.query("select extract(hour from i.created_at at time zone :tz)::int as h, count(*) as c from incidents i "
                + "where i.parent_id is null and not i.simulated and i.created_at >= :from group by 1", params, rs -> {
            int h = rs.getInt("h");
            if (h >= 0 && h < 24) {
                counts[h] = rs.getLong("c");
            }
        });
        List<HourCount> result = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            result.add(new HourCount(h, counts[h]));
        }
        return result;
    }

    // ------------------------------------------------------------------ overview

    public Overview overview(int days) {
        return overview(period(days));
    }

    public Overview overview(Period p) {
        Instant now = clock.instant();
        MapSqlParameterSource params = params(p);
        String dept = scope(p, "i");

        List<ServiceRow> byService = jdbc.query("select coalesce(s.name, 'Unassigned service') as name, "
                + "count(*) filter (where i.created_at >= :from and i.created_at < :to) as created, "
                + "count(*) filter (where i.resolved_at >= :from and i.resolved_at < :to and " + NOT_CANCELLED + ") as resolved, "
                + "count(*) filter (where i.status in " + ACTIVE + ") as open, "
                + "avg(extract(epoch from (i.resolved_at - i.created_at)) / 60) filter (where i.resolved_at >= :from and i.resolved_at < :to "
                + "  and " + NOT_CANCELLED + ") as mttr "
                + "from incidents i left join business_services s on s.id = i.service_id where i.parent_id is null" + dept
                + " and ((i.created_at >= :from and i.created_at < :to) or (i.resolved_at >= :from and i.resolved_at < :to) or i.status in " + ACTIVE + ") "
                + "group by 1 order by created desc, name", params,
                (rs, n) -> new ServiceRow(rs.getString("name"), rs.getLong("created"), rs.getLong("resolved"), rs.getLong("open"),
                        roundOrNull(rs.getObject("mttr"))));

        List<CategoryRow> byCategory = jdbc.query("select i.category, count(*) as c from incidents i where i.parent_id is null "
                + "and i.created_at >= :from and i.created_at < :to" + dept + " group by i.category order by c desc", params,
                (rs, n) -> new CategoryRow(Category.valueOf(rs.getString("category")), rs.getLong("c")));

        List<DepartmentRow> byDepartment = jdbc.query("select coalesce(d.name, 'Unassigned') as name, "
                + "count(*) filter (where i.status in " + ACTIVE + ") as open, "
                + "count(*) filter (where i.resolved_at >= :from and i.resolved_at < :to and " + NOT_CANCELLED + ") as resolved, "
                + "avg(extract(epoch from (i.resolved_at - i.created_at)) / 60) filter (where i.resolved_at >= :from and i.resolved_at < :to "
                + "  and " + NOT_CANCELLED + ") as mttr, "
                + "100.0 * count(*) filter (where sl.completed_at >= :from and sl.completed_at < :to and sl.outcome = 'MET') "
                + "/ nullif(count(*) filter (where sl.completed_at >= :from and sl.completed_at < :to and sl.outcome in ('MET','MISSED')), 0) as sla "
                + "from incidents i left join departments d on d.id = i.department_id left join incident_slas sl on sl.incident_id = i.id "
                + "where i.parent_id is null" + dept + " group by 1 order by open desc, name", params,
                (rs, n) -> new DepartmentRow(rs.getString("name"), rs.getLong("open"), rs.getLong("resolved"),
                        roundOrNull(rs.getObject("mttr")), roundOrNull(rs.getObject("sla"))));

        Map<Priority, PriorityRow> priorityRows = new EnumMap<>(Priority.class);
        jdbc.query("select i.priority, count(*) filter (where i.created_at >= :from and i.created_at < :to) as c, "
                + "avg(extract(epoch from (i.resolved_at - i.created_at)) / 60) filter (where i.resolved_at >= :from and i.resolved_at < :to "
                + "  and " + NOT_CANCELLED + ") as mttr, "
                + "100.0 * count(*) filter (where sl.completed_at >= :from and sl.completed_at < :to and sl.outcome = 'MET') "
                + "/ nullif(count(*) filter (where sl.completed_at >= :from and sl.completed_at < :to and sl.outcome in ('MET','MISSED')), 0) as sla "
                + "from incidents i left join incident_slas sl on sl.incident_id = i.id where i.parent_id is null" + dept
                + " group by i.priority", params, rs -> {
            Priority pr = Priority.valueOf(rs.getString("priority"));
            priorityRows.put(pr, new PriorityRow(pr, rs.getLong("c"), roundOrNull(rs.getObject("mttr")), roundOrNull(rs.getObject("sla"))));
        });
        List<PriorityRow> byPriority = new ArrayList<>();
        for (Priority pr : Priority.values()) {
            byPriority.add(priorityRows.getOrDefault(pr, new PriorityRow(pr, 0, null, null)));
        }

        MapSqlParameterSource agingParams = params(p)
                .addValue("d1", at(now.minus(Duration.ofDays(1)))).addValue("d3", at(now.minus(Duration.ofDays(3))))
                .addValue("d7", at(now.minus(Duration.ofDays(7))));
        Map<String, Object> aging = jdbc.queryForMap("select count(*) filter (where i.created_at >= :d1) as a, "
                + "count(*) filter (where i.created_at < :d1 and i.created_at >= :d3) as b, "
                + "count(*) filter (where i.created_at < :d3 and i.created_at >= :d7) as c, "
                + "count(*) filter (where i.created_at < :d7) as d from incidents i where i.parent_id is null and i.status in " + ACTIVE + dept,
                agingParams);
        List<AgingBucket> backlog = List.of(new AgingBucket("< 1 day", num(aging.get("a"))), new AgingBucket("1-3 days", num(aging.get("b"))),
                new AgingBucket("3-7 days", num(aging.get("c"))), new AgingBucket("> 7 days", num(aging.get("d"))));

        List<RecurringRow> recurring = jdbc.query("select coalesce(s.name, 'General') || ' · ' || initcap(lower(i.category)) as pattern, "
                + "count(*) as c, s.name as service from incidents i left join business_services s on s.id = i.service_id "
                + "where i.created_at >= :from and i.created_at < :to" + dept + " group by s.name, i.category having count(*) >= 2 "
                + "order by c desc limit 8", params,
                (rs, n) -> new RecurringRow(rs.getString("pattern"), rs.getLong("c"), rs.getString("service")));

        String userDept = p.departmentId() == null ? "" : " and u.department_id = :dept";
        List<AgentRow> agents = jdbc.query("select u.id, u.full_name, d.name as dept, "
                + "(select count(*) from incidents i where i.resolved_by_id = u.id and i.resolved_at >= :from and i.resolved_at < :to "
                + "  and i.parent_id is null and " + NOT_CANCELLED + sim(p, "i") + ") as resolved, "
                + "(select avg(extract(epoch from (i.resolved_at - i.created_at)) / 60) from incidents i where i.resolved_by_id = u.id "
                + "  and i.resolved_at >= :from and i.resolved_at < :to and i.parent_id is null and " + NOT_CANCELLED + sim(p, "i") + ") as avg_res, "
                + "(select count(*) from incidents o where o.assignee_id = u.id and o.status in " + ACTIVE + sim(p, "o") + ") as open "
                + "from users u left join departments d on d.id = u.department_id "
                + "where u.active and u.role in ('SUPPORT_AGENT','IT_MANAGER','INCIDENT_MANAGER','CHANGE_MANAGER','ASSET_MANAGER')" + userDept
                + " order by resolved desc, open desc, u.full_name limit 15", params,
                (rs, n) -> new AgentRow(rs.getLong("id"), rs.getString("full_name"), rs.getString("dept"), rs.getLong("resolved"),
                        roundOrNull(rs.getObject("avg_res")), rs.getLong("open")));

        List<String> serviceNames = jdbc.query("select s.name from incidents i join business_services s on s.id = i.service_id "
                + "where i.parent_id is null and i.created_at >= :from and i.created_at < :to" + dept
                + " group by s.name order by count(*) desc, s.name limit 5", params, (rs, n) -> rs.getString(1));
        List<ServiceVolumePoint> serviceVolume = serviceVolume(p, serviceNames);

        return new Overview(p.days(), kpis(p), trend(p), byService, byCategory, byDepartment, byPriority, backlog, recurring, agents,
                serviceVolume, serviceNames, p.fromDate().toString(), p.toDate().toString(), p.departmentId());
    }

    private List<ServiceVolumePoint> serviceVolume(Period p, List<String> serviceNames) {
        ZoneId zone = zone();
        Map<String, Map<String, Long>> byDay = new LinkedHashMap<>();
        for (LocalDate d = p.fromDate(); !d.isAfter(p.toDate()); d = d.plusDays(1)) {
            Map<String, Long> counts = new LinkedHashMap<>();
            serviceNames.forEach(name -> counts.put(name, 0L));
            byDay.put(d.toString(), counts);
        }
        if (!serviceNames.isEmpty()) {
            MapSqlParameterSource params = params(p).addValue("tz", zone.getId()).addValue("names", serviceNames)
                    .addValue("dayFrom", at(p.fromDate().atStartOfDay(zone).toInstant()));
            jdbc.query("select to_char(i.created_at at time zone :tz, 'YYYY-MM-DD') as d, s.name, count(*) as c from incidents i "
                    + "join business_services s on s.id = i.service_id where i.parent_id is null and i.created_at >= :dayFrom "
                    + "and i.created_at < :to and s.name in (:names)" + scope(p, "i") + " group by 1, 2", params, rs -> {
                Map<String, Long> counts = byDay.get(rs.getString("d"));
                if (counts != null) {
                    counts.put(rs.getString("name"), rs.getLong("c"));
                }
            });
        }
        return byDay.entrySet().stream().map(e -> new ServiceVolumePoint(e.getKey(), e.getValue())).toList();
    }

    // ------------------------------------------------------------------ forecast

    public Forecast forecast(int days, int horizon) {
        return forecast(days, horizon, null);
    }

    public Forecast forecast(Period p, int horizon) {
        return forecast(p.days(), horizon, p.departmentId(), p.includeSimulated());
    }

    public Forecast forecast(int days, int horizon, Long departmentId) {
        return forecast(days, horizon, departmentId, false);
    }

    public Forecast forecast(int days, int horizon, Long departmentId, boolean includeSimulated) {
        int window = Math.max(7, Math.min(days, 180));
        int ahead = Math.max(1, Math.min(horizon, 30));
        Instant now = clock.instant();
        ZoneId zone = zone();
        LocalDate today = LocalDate.ofInstant(now, zone);
        // Today's bucket is incomplete, so fit on fully elapsed days only.
        LocalDate last = today.minusDays(1);
        LocalDate first = last.minusDays(window - 1L);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            counts.put(d.toString(), 0L);
        }
        String dept = (departmentId == null ? "" : " and i.department_id = :dept") + (includeSimulated ? "" : " and not i.simulated");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", at(first.atStartOfDay(zone).toInstant()))
                .addValue("to", at(today.atStartOfDay(zone).toInstant())).addValue("tz", zone.getId()).addValue("dept", departmentId);
        jdbc.query("select to_char(i.created_at at time zone :tz, 'YYYY-MM-DD') as d, count(*) as c from incidents i "
                + "where i.parent_id is null and i.created_at >= :from and i.created_at < :to" + dept + " group by 1", p, rs -> {
            if (counts.containsKey(rs.getString("d"))) {
                counts.put(rs.getString("d"), rs.getLong("c"));
            }
        });
        List<Long> values = new ArrayList<>(counts.values());
        ForecastModel.Fit fit = ForecastModel.fit(values);
        List<ForecastPoint> points = ForecastModel.project(fit, values.size(), ahead).stream()
                .map(pt -> new ForecastPoint(last.plusDays(pt.offset()).toString(), pt.predicted(), pt.lower(), pt.upper())).toList();
        double nextWeek = points.stream().limit(7).mapToDouble(ForecastPoint::predicted).sum();

        MapSqlParameterSource sp = new MapSqlParameterSource()
                .addValue("from7", at(now.minus(Duration.ofDays(7)))).addValue("from", at(first.atStartOfDay(zone).toInstant()))
                .addValue("dept", departmentId);
        List<Object[]> rows = jdbc.query("select coalesce(s.name, 'Unassigned service') as name, "
                + "count(*) filter (where i.created_at >= :from7) as last7, count(*) as total "
                + "from incidents i left join business_services s on s.id = i.service_id "
                + "where i.parent_id is null and i.created_at >= :from" + dept + " group by 1 order by total desc limit 10", sp,
                (rs, n) -> new Object[]{rs.getString("name"), rs.getLong("last7"), rs.getLong("total")});
        long windowTotal = values.stream().mapToLong(Long::longValue).sum();
        List<ServiceForecast> byService = rows.stream().map(r -> new ServiceForecast((String) r[0], (Long) r[1],
                windowTotal == 0 ? 0 : round1(nextWeek * (Long) r[2] / windowTotal))).toList();
        List<HistoryPoint> history = counts.entrySet().stream().map(e -> new HistoryPoint(e.getKey(), e.getValue())).toList();
        return new Forecast(history, points, FORECAST_METHOD, Math.round(fit.slope() * 1000) / 1000.0,
                fit.rSquared() == null ? null : Math.round(fit.rSquared() * 1000) / 1000.0, byService);
    }

    // ------------------------------------------------------------------ export

    public ResponseEntity<byte[]> export(int days) {
        return export("csv", period(days));
    }

    /** CSV of the incidents created in the period, or a PDF report of the period's KPIs and breakdowns. Both are audited. */
    public ResponseEntity<byte[]> export(String format, Period p) {
        String f = format == null || format.isBlank() ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("format", f);
        details.put("from", p.fromDate().toString());
        details.put("to", p.toDate().toString());
        details.put("days", p.days());
        details.put("departmentId", p.departmentId() == null ? "" : p.departmentId());
        String suffix = p.fromDate() + "_" + p.toDate();
        if ("pdf".equals(f)) {
            byte[] document = overviewPdf(p);
            details.put("bytes", document.length);
            audit.record("ANALYTICS_EXPORTED", "ANALYTICS", null, details);
            return AnalyticsPdf.response("nexus360-analytics-" + suffix + ".pdf", document);
        }
        if (!"csv".equals(f)) {
            throw ApiException.badRequest("format must be csv or pdf");
        }
        List<List<Object>> rows = incidentRows(p);
        details.put("rows", rows.size());
        audit.record("ANALYTICS_EXPORTED", "ANALYTICS", null, details);
        return Csv.response("nexus360-analytics-" + suffix + ".csv", INCIDENT_CSV_HEADER, rows);
    }

    /** Header of the incident-row CSV (analytics export and the INCIDENTS scheduled report). */
    public static final List<String> INCIDENT_CSV_HEADER = List.of("number", "title", "priority", "status", "category",
            "service", "department", "assignee", "major", "created_at", "first_response_at", "resolved_at", "resolution_code",
            "resolution_minutes", "sla_outcome", "parent", "demo");

    /** The analytics PDF report of the period (not audited; callers audit the export or report run). */
    @Transactional(readOnly = true)
    public byte[] overviewPdf(Period p) {
        Overview overview = overview(p);
        String department = p.departmentId() == null ? null : departments.findById(p.departmentId()).map(d -> d.getName()).orElse(null);
        return pdf.render(overview, department, clock.instant(), zone());
    }

    /** Incidents created in the period as CSV rows in {@link #INCIDENT_CSV_HEADER} order (not audited). */
    @Transactional(readOnly = true)
    public List<List<Object>> incidentRows(Period p) {
        return jdbc.query("select i.number, i.title, i.priority, i.status, i.category, s.name as service, d.name as dept, "
                + "u.full_name as assignee, i.is_major, i.created_at, i.first_response_at, i.resolved_at, i.resolution_code, "
                + "round(extract(epoch from (i.resolved_at - i.created_at)) / 60) as minutes, sl.outcome, p.number as parent, i.is_demo "
                + "from incidents i left join business_services s on s.id = i.service_id left join departments d on d.id = i.department_id "
                + "left join users u on u.id = i.assignee_id left join incident_slas sl on sl.incident_id = i.id "
                + "left join incidents p on p.id = i.parent_id where i.created_at >= :from and i.created_at < :to" + scope(p, "i")
                + " order by i.created_at desc",
                params(p), (rs, n) -> java.util.Arrays.asList(rs.getString("number"), rs.getString("title"),
                        rs.getString("priority"), rs.getString("status"), rs.getString("category"), rs.getString("service"),
                        rs.getString("dept"), rs.getString("assignee"), rs.getBoolean("is_major"), rs.getString("created_at"),
                        rs.getString("first_response_at"), rs.getString("resolved_at"), rs.getString("resolution_code"), rs.getObject("minutes"),
                        rs.getString("outcome"), rs.getString("parent"), rs.getBoolean("is_demo")));
    }

    // ------------------------------------------------------------------ helpers

    public ZoneId zone() {
        List<String> zones = jdbc.queryForList("select timezone from business_hours_calendars where is_default limit 1",
                new MapSqlParameterSource(), String.class);
        try {
            return zones.isEmpty() ? ZoneOffset.UTC : ZoneId.of(zones.get(0));
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static MapSqlParameterSource params(Period p) {
        return new MapSqlParameterSource().addValue("from", at(p.from())).addValue("to", at(p.to()))
                .addValue("prevFrom", at(p.prevFrom())).addValue("dept", p.departmentId());
    }

    /** Department filter plus the simulated-data exclusion on the incident alias. */
    private static String scope(Period p, String alias) {
        return dept(p, alias) + sim(p, alias);
    }

    /** Excludes simulated rows of the alias unless the period explicitly includes them ({@code includeSimulated=true}). */
    static String sim(Period p, String alias) {
        return p.includeSimulated() ? "" : " and not " + alias + ".simulated";
    }

    /** Department filter on the alias's assignment group; empty when no department was requested. */
    private static String dept(Period p, String alias) {
        return p.departmentId() == null ? "" : " and " + alias + ".department_id = :dept";
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static int clampDays(int days) {
        return Math.max(1, Math.min(days, 365));
    }

    private static int statusRank(ServiceStatus status) {
        return switch (status) {
            case OUTAGE -> 0;
            case DEGRADED -> 1;
            case MAINTENANCE -> 2;
            case OPERATIONAL -> 3;
        };
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static Double dbl(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    private static Double roundOrNull(Object o) {
        return o == null ? null : round1(((Number) o).doubleValue());
    }

    private static Double pct(long part, long whole) {
        return whole == 0 ? null : round1(part * 100.0 / whole);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}

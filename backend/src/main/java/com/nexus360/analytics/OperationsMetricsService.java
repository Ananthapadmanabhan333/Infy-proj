package com.nexus360.analytics;

import com.nexus360.analytics.AnalyticsService.Period;
import com.nexus360.analytics.OperationsDtos.ActivityEntry;
import com.nexus360.analytics.OperationsDtos.AlertBlock;
import com.nexus360.analytics.OperationsDtos.AlertVolume;
import com.nexus360.analytics.OperationsDtos.AssetLifecycle;
import com.nexus360.analytics.OperationsDtos.ChangeBlock;
import com.nexus360.analytics.OperationsDtos.ChangeOutcomes;
import com.nexus360.analytics.OperationsDtos.CommandCenter;
import com.nexus360.analytics.OperationsDtos.DayCount;
import com.nexus360.analytics.OperationsDtos.DepartmentPerformance;
import com.nexus360.analytics.OperationsDtos.Duration;
import com.nexus360.analytics.OperationsDtos.FirstContact;
import com.nexus360.analytics.OperationsDtos.IncidentBlock;
import com.nexus360.analytics.OperationsDtos.MajorRef;
import com.nexus360.analytics.OperationsDtos.MetricDefinition;
import com.nexus360.analytics.OperationsDtos.Mttd;
import com.nexus360.analytics.OperationsDtos.OnCallPerformance;
import com.nexus360.analytics.OperationsDtos.OnCallSchedulePerformance;
import com.nexus360.analytics.OperationsDtos.OperationsMetrics;
import com.nexus360.analytics.OperationsDtos.ProblemRecurrence;
import com.nexus360.analytics.OperationsDtos.ServiceAvailability;
import com.nexus360.analytics.OperationsDtos.WorkflowBlock;
import com.nexus360.cmdb.ServiceHealthService;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.oncall.OnCallService;
import com.nexus360.oncall.OnCallService.PerformanceRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release 3 operational metrics (docs/RELEASE3_SPEC.md §5 BE-4 "Analytics") and the Command Center block, computed from stored
 * records only. Every metric takes the analytics {@link Period} ({@code from}/{@code to} dates or the last {@code days}, plus an
 * optional {@code departmentId}); {@link #definitions()} documents how each is computed (served by
 * {@code GET /api/analytics/definitions} and docs/API.md). Incident metrics count top-level incidents (duplicate reports are
 * represented by their parent) and never count cancellations as resolved, as the existing analytics do.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationsMetricsService {

    private static final String ACTIVE = IncidentStatus.activeSqlList();
    private static final String NOT_CANCELLED = "coalesce(i.resolution_code, '') <> 'CANCELLED'";
    private static final List<String> SEVERITIES = List.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO");
    private static final List<String> SOURCES = List.of("PROMETHEUS", "GRAFANA", "CLOUDWATCH", "GENERIC", "INTEGRATION", "MANUAL");
    private static final List<String> ASSET_STATUSES = List.of("IN_STOCK", "IN_USE", "IN_REPAIR", "RETIRED", "DISPOSED");

    private final NamedParameterJdbcTemplate jdbc;
    private final ServiceHealthService serviceHealth;
    private final OnCallService oncall;
    private final Clock clock;

    // ------------------------------------------------------------------ operations metrics

    public OperationsMetrics metrics(Period p) {
        MapSqlParameterSource params = params(p);
        return new OperationsMetrics(p.fromDate().toString(), p.toDate().toString(), p.days(), p.departmentId(), clock.instant(), mtta(p, params),
                mttd(p, params), firstContact(p, params), changes(p, params), problemRecurrence(p, params), alertVolume(p, params), assets(p, params),
                onCall(p), departmentPerformance(p, params), serviceAvailability(p, params));
    }

    Duration mtta(Period p, MapSqlParameterSource params) {
        Map<String, Object> r = jdbc.queryForMap("select avg(extract(epoch from (i.first_response_at - i.created_at)) / 60) as m, count(*) as n "
                + "from incidents i where i.parent_id is null and i.first_response_at is not null and i.created_at >= :from and i.created_at < :to"
                + scope(p, "i"), params);
        return new Duration(round(r.get("m")), num(r.get("n")));
    }

    Mttd mttd(Period p, MapSqlParameterSource params) {
        Map<String, Object> r = jdbc.queryForMap("select count(*) as total, count(d.first_alert) as n, "
                + "avg(extract(epoch from (d.created_at - d.first_alert)) / 60) as m from (select i.created_at, (select min(a.first_seen_at) "
                + "from alerts a where a.first_seen_at <= i.created_at and (a.incident_id = i.id or a.incident_id in "
                + "(select c.id from incidents c where c.parent_id = i.id))) as first_alert from incidents i where i.parent_id is null "
                + "and i.created_at >= :from and i.created_at < :to and " + NOT_CANCELLED + scope(p, "i") + ") d", params);
        long n = num(r.get("n"));
        return new Mttd(n == 0 ? null : round(r.get("m")), n, num(r.get("total")) - n);
    }

    FirstContact firstContact(Period p, MapSqlParameterSource params) {
        Map<String, Object> f = jdbc.queryForMap("select count(*) as resolved, coalesce(sum(case when "
                + "(select count(distinct h.new_value) from incident_history h where h.incident_id = i.id and h.field = 'assignee' "
                + "  and h.new_value is not null) <= 1 "
                + "and not exists (select 1 from incident_history h where h.incident_id = i.id and h.field = 'status' "
                + "  and (h.new_value = 'REOPENED' or (h.old_value = 'RESOLVED' and h.new_value = 'IN_PROGRESS'))) "
                + "then 1 else 0 end), 0) as fcr "
                + "from incidents i where i.parent_id is null and i.resolved_at >= :from and i.resolved_at < :to and " + NOT_CANCELLED + scope(p, "i"),
                params);
        long resolved = num(f.get("resolved"));
        long fcr = num(f.get("fcr"));
        return new FirstContact(pct(fcr, resolved), resolved, fcr);
    }

    ChangeOutcomes changes(Period p, MapSqlParameterSource params) {
        Map<String, Object> r = jdbc.queryForMap("select count(*) filter (where c.status = 'COMPLETED') as completed, "
                + "count(*) filter (where c.status = 'FAILED') as failed, count(*) filter (where c.status = 'ROLLED_BACK') as rolled_back "
                + "from changes c left join teams t on t.id = c.team_id where c.status in ('COMPLETED', 'FAILED', 'ROLLED_BACK') "
                + "and coalesce(c.closed_at, c.actual_end, c.updated_at) >= :from and coalesce(c.closed_at, c.actual_end, c.updated_at) < :to"
                + dept(p, "t"), params);
        long completed = num(r.get("completed"));
        long failed = num(r.get("failed"));
        long rolledBack = num(r.get("rolled_back"));
        long finished = completed + failed + rolledBack;
        return new ChangeOutcomes(finished, completed, failed, rolledBack, pct(completed, finished), pct(failed + rolledBack, finished));
    }

    ProblemRecurrence problemRecurrence(Period p, MapSqlParameterSource params) {
        Long known = jdbc.queryForObject("select count(*) from problems p where p.known_error_at is not null and p.known_error_at < :to "
                + "and (p.closed_at is null or p.closed_at >= :from)" + dept(p, "p"), params, Long.class);
        Map<String, Object> r = jdbc.queryForMap("select count(distinct i.id) as incidents, count(distinct p.id) as problems from problems p "
                + "join problem_incidents pi on pi.problem_id = p.id join incidents i on i.id = pi.incident_id where p.known_error_at is not null "
                + "and i.created_at > p.known_error_at and i.created_at >= :from and i.created_at < :to" + dept(p, "p") + AnalyticsService.sim(p, "i"), params);
        long k = known == null ? 0 : known;
        long problems = num(r.get("problems"));
        return new ProblemRecurrence(k, problems, num(r.get("incidents")), pct(problems, k));
    }

    AlertVolume alertVolume(Period p, MapSqlParameterSource params) {
        ZoneId zone = zone();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        SEVERITIES.forEach(s -> bySeverity.put(s, 0L));
        Map<String, Long> bySource = new LinkedHashMap<>();
        SOURCES.forEach(s -> bySource.put(s, 0L));
        Map<String, Long> perDay = new LinkedHashMap<>();
        for (LocalDate d = p.fromDate(); !d.isAfter(p.toDate()); d = d.plusDays(1)) {
            perDay.put(d.toString(), 0L);
        }
        long[] totals = new long[4];
        jdbc.query("select a.severity, a.source_kind, a.status, a.incident_id, a.occurrence_count, "
                + "to_char(a.first_seen_at at time zone :tz, 'YYYY-MM-DD') as d from alerts a left join teams t on t.id = a.team_id "
                + "left join business_services s on s.id = a.service_id where a.first_seen_at >= :from and a.first_seen_at < :to" + AnalyticsService.sim(p, "a")
                + (p.departmentId() == null ? "" : " and coalesce(t.department_id, s.department_id) = :dept"), params.addValue("tz", zone.getId()), rs -> {
            totals[0]++;
            totals[1] += rs.getLong("occurrence_count");
            bySeverity.merge(rs.getString("severity"), 1L, Long::sum);
            bySource.merge(rs.getString("source_kind"), 1L, Long::sum);
            if ("SUPPRESSED".equals(rs.getString("status"))) {
                totals[2]++;
            }
            if (rs.getObject("incident_id") != null) {
                totals[3]++;
            }
            perDay.computeIfPresent(rs.getString("d"), (k, v) -> v + 1);
        });
        return new AlertVolume(totals[0], totals[1], bySeverity, bySource, totals[2], totals[3],
                perDay.entrySet().stream().map(e -> new DayCount(e.getKey(), e.getValue())).toList());
    }

    AssetLifecycle assets(Period p, MapSqlParameterSource params) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        ASSET_STATUSES.forEach(s -> byStatus.put(s, 0L));
        String dept = dept(p, "a");
        jdbc.query("select a.status, count(*) as c from assets a where true" + dept + " group by a.status", params,
                rs -> {
                    byStatus.put(rs.getString("status"), rs.getLong("c"));
                });
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone());
        MapSqlParameterSource q = params.addValue("fromDate", java.sql.Date.valueOf(p.fromDate())).addValue("toDate", java.sql.Date.valueOf(p.toDate()))
                .addValue("today", java.sql.Date.valueOf(today)).addValue("in30", java.sql.Date.valueOf(today.plusDays(30)));
        Map<String, Object> r = jdbc.queryForMap("select "
                + "count(*) filter (where (a.purchase_date is not null and a.purchase_date between :fromDate and :toDate) or (a.purchase_date is null "
                + "  and a.created_at >= :from and a.created_at < :to)) as acquired, "
                + "count(*) filter (where a.retired_at >= :from and a.retired_at < :to) as retired, "
                + "count(*) filter (where a.disposed_at >= :from and a.disposed_at < :to) as disposed, "
                + "avg(cast(:toDate as date) - a.purchase_date) filter (where a.asset_type <> 'SOFTWARE_LICENSE' and a.purchase_date is not null "
                + "  and a.purchase_date <= :toDate and a.status in ('IN_STOCK', 'IN_USE', 'IN_REPAIR')) as age, "
                + "count(*) filter (where a.asset_type <> 'SOFTWARE_LICENSE' and a.status in ('IN_STOCK', 'IN_USE', 'IN_REPAIR') "
                + "  and a.warranty_expires between :today and :in30) as expiring "
                + "from assets a where true" + dept, q);
        return new AssetLifecycle(byStatus, num(r.get("acquired")), num(r.get("retired")), num(r.get("disposed")), round(r.get("age")),
                num(r.get("expiring")));
    }

    OnCallPerformance onCall(Period p) {
        List<PerformanceRow> rows = oncall.performance(p.from(), p.to(), p.departmentId());
        long alerts = 0;
        long pages = 0;
        long ack = 0;
        double ackSum = 0;
        long ackN = 0;
        long escalated = 0;
        List<OnCallSchedulePerformance> bySchedule = new ArrayList<>();
        for (PerformanceRow r : rows) {
            alerts += r.alertsPaged();
            pages += r.pages();
            ack += r.acknowledged();
            ackSum += r.ackMinutesSum();
            ackN += r.ackSamples();
            escalated += r.escalated();
            bySchedule.add(new OnCallSchedulePerformance(r.scheduleId(), r.scheduleName(), r.teamId(), r.teamName(), r.alertsPaged(), r.pages(),
                    r.acknowledged(), pct(r.acknowledged(), r.alertsPaged()), r.ackSamples() == 0 ? null : round1(r.ackMinutesSum() / r.ackSamples()),
                    r.escalated()));
        }
        return new OnCallPerformance(alerts, pages, ack, pct(ack, alerts), ackN == 0 ? null : round1(ackSum / ackN), escalated, bySchedule);
    }

    List<DepartmentPerformance> departmentPerformance(Period p, MapSqlParameterSource params) {
        String filter = p.departmentId() == null ? "" : " and d.id = :dept";
        return jdbc.query("select d.id, d.name, x.created, x.resolved, x.open, x.mtta, x.mttr, s.met, s.done, f.fcr, f.resolved as fcr_base "
                + "from departments d "
                + "cross join lateral (select count(*) filter (where i.created_at >= :from and i.created_at < :to) as created, "
                + "  count(*) filter (where i.resolved_at >= :from and i.resolved_at < :to and " + NOT_CANCELLED + ") as resolved, "
                + "  count(*) filter (where i.status in " + ACTIVE + ") as open, "
                + "  avg(extract(epoch from (i.first_response_at - i.created_at)) / 60) filter (where i.first_response_at is not null "
                + "    and i.created_at >= :from and i.created_at < :to) as mtta, "
                + "  avg(extract(epoch from (i.resolved_at - i.created_at)) / 60) filter (where i.resolved_at >= :from and i.resolved_at < :to "
                + "    and " + NOT_CANCELLED + ") as mttr "
                + "  from incidents i where i.parent_id is null and i.department_id = d.id" + AnalyticsService.sim(p, "i") + ") x "
                + "cross join lateral (select count(*) filter (where sl.outcome = 'MET') as met, count(*) filter (where sl.outcome in ('MET', 'MISSED')) as done "
                + "  from incident_slas sl join incidents i on i.id = sl.incident_id where i.department_id = d.id" + AnalyticsService.sim(p, "i")
                + "  and sl.completed_at >= :from "
                + "  and sl.completed_at < :to) s "
                + "cross join lateral (select count(*) as resolved, coalesce(sum(case when (select count(distinct h.new_value) from incident_history h "
                + "    where h.incident_id = i.id and h.field = 'assignee' and h.new_value is not null) <= 1 and not exists (select 1 from incident_history h "
                + "    where h.incident_id = i.id and h.field = 'status' and (h.new_value = 'REOPENED' or (h.old_value = 'RESOLVED' "
                + "    and h.new_value = 'IN_PROGRESS'))) then 1 else 0 end), 0) as fcr from incidents i where i.parent_id is null and i.department_id = d.id "
                + "    and i.resolved_at >= :from and i.resolved_at < :to and " + NOT_CANCELLED + AnalyticsService.sim(p, "i") + ") f "
                + "where (x.created > 0 or x.resolved > 0 or x.open > 0" + (p.departmentId() == null ? "" : " or true") + ")" + filter
                + " order by x.created desc, x.open desc, d.name", params,
                (rs, n) -> new DepartmentPerformance(rs.getLong("id"), rs.getString("name"), rs.getLong("created"), rs.getLong("resolved"),
                        rs.getLong("open"), round(rs.getObject("mtta")), round(rs.getObject("mttr")), pct(rs.getLong("met"), rs.getLong("done")),
                        pct(rs.getLong("fcr"), rs.getLong("fcr_base"))));
    }

    List<ServiceAvailability> serviceAvailability(Period p, MapSqlParameterSource params) {
        Instant now = clock.instant();
        Instant end = p.to().isAfter(now) ? now : p.to();
        double window = Math.max(0, java.time.Duration.between(p.from(), end).getSeconds());
        Map<Long, ServiceHealthService.Downtime> downtime = end.isAfter(p.from()) ? serviceHealth.downtime(p.from(), end, p.includeSimulated()) : Map.of();
        List<ServiceAvailability> result = jdbc.query("select s.id, s.code, s.name, s.tier from business_services s where true" + dept(p, "s")
                + " order by s.tier, s.name", params, (rs, n) -> {
            ServiceHealthService.Downtime d = downtime.get(rs.getLong("id"));
            return new ServiceAvailability(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getInt("tier"),
                    window <= 0 ? null : d == null ? 100.0 : d.availability(window), d == null ? 0 : round1(d.outageSeconds() / 60),
                    d == null ? 0 : round1(d.degradedSeconds() / 60), d == null ? 0 : d.incidents());
        });
        List<ServiceAvailability> sorted = new ArrayList<>(result);
        sorted.sort(Comparator.comparing((ServiceAvailability s) -> s.availability() == null ? 100.0 : s.availability()).thenComparing(ServiceAvailability::tier)
                .thenComparing(ServiceAvailability::name));
        return sorted;
    }

    // ------------------------------------------------------------------ Command Center

    /** The Command Center block of the staff dashboard: live operational state plus period figures, all from the database. */
    public CommandCenter commandCenter(Period p) {
        Instant now = clock.instant();
        MapSqlParameterSource params = params(p).addValue("now", at(now)).addValue("d1", at(now.minus(java.time.Duration.ofHours(24))))
                .addValue("in7", at(now.plus(java.time.Duration.ofDays(7))));
        String dept = scope(p, "i");
        Map<String, Object> inc = jdbc.queryForMap("select count(*) filter (where i.status in " + ACTIVE + ") as open, "
                + "count(*) filter (where i.status in " + ACTIVE + " and i.priority = 'CRITICAL') as critical, "
                + "count(*) filter (where i.status in " + ACTIVE + " and i.is_major) as major, "
                + "count(*) filter (where i.created_at >= :from and i.created_at < :to) as created, "
                + "count(*) filter (where i.resolved_at >= :from and i.resolved_at < :to and " + NOT_CANCELLED + ") as resolved, "
                + "avg(extract(epoch from (i.first_response_at - i.created_at)) / 60) filter (where i.first_response_at is not null "
                + "  and i.created_at >= :from and i.created_at < :to) as mtta from incidents i where i.parent_id is null" + dept, params);
        Map<String, Object> sla = jdbc.queryForMap("select count(*) filter (where sl.breached_at >= :from and sl.breached_at < :to) as breaches, "
                + "count(*) filter (where sl.health = 'AT_RISK' and sl.clock_status <> 'STOPPED' and i.status in " + ACTIVE + ") as at_risk "
                + "from incident_slas sl join incidents i on i.id = sl.incident_id where i.parent_id is null" + dept, params);
        IncidentBlock incidents = new IncidentBlock(num(inc.get("open")), num(inc.get("critical")), num(inc.get("major")), num(inc.get("created")),
                num(inc.get("resolved")), num(sla.get("breaches")), num(sla.get("at_risk")), round(inc.get("mtta")));
        String alertDept = p.departmentId() == null ? "" : " and coalesce(t.department_id, s.department_id) = :dept";
        Map<String, Object> al = jdbc.queryForMap("select count(*) filter (where a.status in ('OPEN', 'ACKNOWLEDGED')) as active, "
                + "count(*) filter (where a.status = 'OPEN') as open, count(*) filter (where a.status = 'ACKNOWLEDGED') as ack, "
                + "count(*) filter (where a.status in ('OPEN', 'ACKNOWLEDGED') and a.severity = 'CRITICAL') as critical, "
                + "count(*) filter (where a.status = 'OPEN' and a.escalation_level > 0) as escalated, "
                + "count(*) filter (where a.status = 'RESOLVED' and a.resolved_at >= :d1) as resolved24 "
                + "from alerts a left join teams t on t.id = a.team_id left join business_services s on s.id = a.service_id where true" + alertDept
                + AnalyticsService.sim(p, "a"), params);
        AlertBlock alerts = new AlertBlock(num(al.get("active")), num(al.get("open")), num(al.get("ack")), num(al.get("critical")),
                num(al.get("escalated")), num(al.get("resolved24")));
        String changeDept = p.departmentId() == null ? "" : " and t.department_id = :dept";
        Map<String, Object> ch = jdbc.queryForMap("select count(*) filter (where c.status = 'AWAITING_APPROVAL') as awaiting, "
                + "count(*) filter (where c.status in ('APPROVED', 'SCHEDULED') and c.planned_start >= :now and c.planned_start < :in7) as scheduled, "
                + "count(*) filter (where c.status = 'IMPLEMENTING') as implementing, "
                + "count(*) filter (where c.status = 'COMPLETED' and coalesce(c.closed_at, c.actual_end, c.updated_at) >= :from "
                + "  and coalesce(c.closed_at, c.actual_end, c.updated_at) < :to) as completed, "
                + "count(*) filter (where c.status in ('FAILED', 'ROLLED_BACK') and coalesce(c.closed_at, c.actual_end, c.updated_at) >= :from "
                + "  and coalesce(c.closed_at, c.actual_end, c.updated_at) < :to) as failed, "
                + "count(*) filter (where c.change_type = 'EMERGENCY' and c.status not in ('COMPLETED', 'FAILED', 'ROLLED_BACK', 'REJECTED', 'CANCELLED')) as emergency "
                + "from changes c left join teams t on t.id = c.team_id where true" + changeDept, params);
        ChangeBlock changes = new ChangeBlock(num(ch.get("awaiting")), num(ch.get("scheduled")), num(ch.get("implementing")), num(ch.get("completed")),
                num(ch.get("failed")), num(ch.get("emergency")));
        Map<String, Object> wf = jdbc.queryForMap("select count(*) filter (where e.started_at >= :d1) as total, "
                + "count(*) filter (where e.started_at >= :d1 and e.status = 'SUCCEEDED') as ok, "
                + "count(*) filter (where e.started_at >= :d1 and e.status = 'FAILED') as failed, "
                + "count(*) filter (where e.status in ('RUNNING', 'RETRY_SCHEDULED')) as running from workflow_executions e", params);
        WorkflowBlock workflows = new WorkflowBlock(num(wf.get("total")), num(wf.get("ok")), num(wf.get("failed")), num(wf.get("running")));
        List<MajorRef> majors = jdbc.query("select m.id, m.number, i.title, m.status, u.full_name, m.declared_at from major_incidents m "
                + "join incidents i on i.id = m.incident_id left join users u on u.id = m.commander_id where m.status in ('ACTIVE', 'MONITORING')"
                + dept + " order by m.declared_at desc limit 5", params, (rs, n) -> new MajorRef(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()));
        List<DepartmentPerformance> departments = departmentPerformance(p, params(p));
        return new CommandCenter(incidents, alerts, oncall.onCall(null, now), changes, workflows, majors,
                departments.size() > 8 ? departments.subList(0, 8) : departments, recentActivity(p, 15));
    }

    /** Newest events across incidents, alerts, changes, deployments and major incidents. */
    List<ActivityEntry> recentActivity(Period p, int limit) {
        MapSqlParameterSource params = params(p).addValue("n", limit);
        List<ActivityEntry> all = new ArrayList<>();
        all.addAll(jdbc.query("select h.created_at, h.event_type, h.field, h.new_value, h.message, h.actor_name, i.id, i.number, i.title "
                + "from incident_history h join incidents i on i.id = h.incident_id where i.parent_id is null "
                + "and h.event_type in ('CREATED', 'STATUS_CHANGED', 'ESCALATED', 'ASSIGNED')" + scope(p, "i") + " order by h.created_at desc, h.id desc limit :n",
                params, (rs, n) -> new ActivityEntry("INCIDENT", rs.getTimestamp(1).toInstant(), rs.getString("number") + " " + incidentEvent(rs.getString(2),
                        rs.getString(3), rs.getString(4)), truncate(rs.getString("message") == null ? rs.getString("title") : rs.getString("message"), 200),
                        rs.getString("actor_name"), "/incidents/" + rs.getLong("id"))));
        String alertDept = p.departmentId() == null ? "" : " and coalesce(t.department_id, s.department_id) = :dept";
        all.addAll(jdbc.query("select e.created_at, e.event_type, e.note, e.actor_name, a.id, a.number, a.title from alert_events e join alerts a on a.id = e.alert_id "
                + "left join teams t on t.id = a.team_id left join business_services s on s.id = a.service_id "
                + "where e.event_type in ('CREATED', 'REOPENED', 'ACKNOWLEDGED', 'RESOLVED', 'ESCALATED', 'CONVERTED')" + alertDept + AnalyticsService.sim(p, "a")
                + " order by e.created_at desc, e.id desc limit :n", params, (rs, n) -> new ActivityEntry("ALERT", rs.getTimestamp(1).toInstant(),
                rs.getString("number") + " " + rs.getString(2).toLowerCase().replace('_', ' '), truncate(rs.getString("note") == null ? rs.getString("title")
                : rs.getString("note"), 200), rs.getString("actor_name"), "/alerts/" + rs.getLong("id"))));
        String changeDept = p.departmentId() == null ? "" : " and t.department_id = :dept";
        all.addAll(jdbc.query("select h.created_at, h.action, h.to_value, h.actor_name, c.id, c.number, c.title from change_history h join changes c "
                + "on c.id = h.change_id left join teams t on t.id = c.team_id where h.action in ('CREATED', 'STATUS_CHANGED')" + changeDept
                + " order by h.created_at desc, h.id desc limit :n", params, (rs, n) -> new ActivityEntry("CHANGE", rs.getTimestamp(1).toInstant(),
                rs.getString("number") + ("STATUS_CHANGED".equals(rs.getString(2)) ? " → " + rs.getString(3) : " created"), truncate(rs.getString("title"), 200),
                rs.getString("actor_name"), "/changes/" + rs.getLong("id"))));
        all.addAll(jdbc.query("select d.started_at, d.number, d.version, d.environment, d.status, d.actor_name, d.id, s.name from deployments d "
                + "join business_services s on s.id = d.service_id where true" + dept(p, "s") + AnalyticsService.sim(p, "d") + " order by d.started_at desc, d.id desc limit :n", params,
                (rs, n) -> new ActivityEntry("DEPLOYMENT", rs.getTimestamp(1).toInstant(), rs.getString(2) + " " + rs.getString("name") + " " + rs.getString(3)
                        + " to " + rs.getString(4), rs.getString(5), rs.getString(6), "/deployments/" + rs.getLong(7))));
        all.addAll(jdbc.query("select t.occurred_at, t.entry_type, t.body, t.actor_name, m.id, m.number from major_incident_timeline t "
                + "join major_incidents m on m.id = t.major_incident_id join incidents i on i.id = m.incident_id where true" + scope(p, "i")
                + " order by t.occurred_at desc, t.id desc limit :n", params, (rs, n) -> new ActivityEntry("MAJOR_INCIDENT", rs.getTimestamp(1).toInstant(),
                rs.getString(6) + " " + rs.getString(2).toLowerCase(), truncate(rs.getString(3), 200), rs.getString(4), "/major-incidents/" + rs.getLong(5))));
        all.sort(Comparator.comparing(ActivityEntry::at).reversed());
        return all.size() > limit ? List.copyOf(all.subList(0, limit)) : all;
    }

    private static String incidentEvent(String type, String field, String value) {
        return switch (type) {
            case "CREATED" -> "created";
            case "STATUS_CHANGED" -> value == null ? "status changed" : "→ " + value;
            case "ESCALATED" -> "escalated";
            case "ASSIGNED" -> field == null || value == null ? "assigned" : field + " → " + value;
            default -> type.toLowerCase();
        };
    }

    // ------------------------------------------------------------------ definitions

    public List<MetricDefinition> definitions() {
        List<String> period = List.of("from", "to", "days", "departmentId");
        return List.of(
                new MetricDefinition("mtta", "Mean time to acknowledge (MTTA)", "minutes",
                        "Average of (first response − created) over top-level incidents created in the period that have a first response",
                        List.of("incidents"), period, "First response = the first status change out of NEW/ASSIGNED, a public reply or SLA response by staff."),
                new MetricDefinition("mttd", "Mean time to detect (MTTD)", "minutes",
                        "Average of (incident created − first linked alert fired) over non-cancelled top-level incidents created in the period that have "
                                + "a linked alert (on the incident or one of its duplicate reports) which fired at or before the incident was recorded",
                        List.of("incidents", "alerts"), period, "Incidents without such an alert have no detection lead and are counted in "
                        + "incidentsWithoutAlert; minutes is null (n/a) when no incident has one."),
                new MetricDefinition("mttr", "Mean time to resolve (MTTR)", "minutes",
                        "Average of (resolved − created) over top-level incidents resolved in the period, cancellations excluded",
                        List.of("incidents"), period, "Existing KPI (dashboard and overview kpis.mttrMinutes)."),
                new MetricDefinition("firstContactResolution", "First-contact resolution", "%",
                        "Incidents resolved in the period with at most one distinct assignee in their history and never reopened ÷ incidents resolved "
                                + "in the period × 100", List.of("incidents", "incident_history"), period,
                        "Same rule as kpis.firstCallResolution; top-level, cancellations excluded."),
                new MetricDefinition("slaCompliance", "SLA compliance", "%", "Resolution SLA clocks completed in the period with outcome MET ÷ clocks "
                        + "completed MET or MISSED × 100", List.of("incident_slas"), period, "Existing KPI."),
                new MetricDefinition("changes.successRate", "Change success rate", "%",
                        "COMPLETED ÷ (COMPLETED + FAILED + ROLLED_BACK) × 100 over changes that finished in the period (closed_at, else actual end)",
                        List.of("changes"), period, "Department = the department of the change's team."),
                new MetricDefinition("changes.failureRate", "Change failure rate", "%", "(FAILED + ROLLED_BACK) ÷ finished changes × 100 (same population)",
                        List.of("changes"), period, null),
                new MetricDefinition("problemRecurrence", "Problem recurrence", "%",
                        "Known-error problems that had at least one new linked incident created after the known error was recorded (and in the period) ÷ "
                                + "known-error problems that existed in the period (known error before the period end, not closed before its start) × 100",
                        List.of("problems", "problem_incidents", "incidents"), period, "recurringIncidents counts those incidents."),
                new MetricDefinition("alertVolume", "Alert volume", "alerts",
                        "Alerts first seen in the period, by severity, source and day (organisation time zone); occurrences = sum of deduplicated repeats; "
                                + "suppressed = currently SUPPRESSED; withIncident = linked to an incident", List.of("alerts"), period,
                        "Department = the routed team's department, else the service's department."),
                new MetricDefinition("assetLifecycle", "Asset lifecycle", "assets",
                        "byStatus: current assets per lifecycle status; acquired: purchase date in the period (else created in the period); retired/disposed: "
                                + "lifecycle date in the period; averageAgeDays: mean (period end date − purchase date) of in-stock, in-use and in-repair hardware; "
                                + "warrantyExpiringNext30Days: active hardware whose warranty ends within 30 days from today",
                        List.of("assets"), period, "Department = the asset's owning department."),
                new MetricDefinition("onCall", "On-call performance", "minutes / %",
                        "Per alert paged in the period (first page in the period): acknowledged = the alert has been acknowledged; avgAckMinutes = mean of "
                                + "(acknowledged − first page) over acknowledged alerts; ackRate = acknowledged ÷ alerts paged × 100; escalated = alerts that paged "
                                + "level 2 or higher", List.of("oncall_pages", "alerts", "oncall_schedules"), period,
                        "Department = the schedule team's department. pages = individual pages sent."),
                new MetricDefinition("departmentPerformance", "Department performance", "per department",
                        "Per assignment department: incidents created and resolved in the period, open now, MTTA, MTTR, SLA compliance and first-contact "
                                + "resolution with the definitions above", List.of("incidents", "incident_slas", "incident_history", "departments"), period, null),
                new MetricDefinition("serviceAvailability", "Service availability", "%",
                        "100 − (merged P1 downtime + 25 % of merged P2 time) ÷ window × 100 per business service, window = the period up to now; the same "
                                + "rule as the service and status-page availability figures", List.of("incidents", "business_services"), period,
                        "Contributing incidents: top-level, not cancelled, priority CRITICAL or HIGH, overlapping the window."),
                new MetricDefinition("commandCenter.slaBreachesInPeriod", "SLA breaches", "clocks",
                        "Resolution SLA clocks of top-level incidents whose breach time falls in the period", List.of("incident_slas"), period, null),
                new MetricDefinition("commandCenter.workflows", "Workflow executions (24 h)", "executions",
                        "Workflow executions started in the last 24 hours (total, succeeded, failed) and executions running or waiting for a retry now",
                        List.of("workflow_executions"), List.of(), "Not filtered by period or department."));
    }

    // ------------------------------------------------------------------ helpers

    private static MapSqlParameterSource params(Period p) {
        return new MapSqlParameterSource().addValue("from", at(p.from())).addValue("to", at(p.to())).addValue("dept", p.departmentId());
    }

    /** Department filter plus the simulated-data exclusion (default) on the incident alias. */
    private static String scope(Period p, String alias) {
        return dept(p, alias) + AnalyticsService.sim(p, alias);
    }

    private static String dept(Period p, String alias) {
        return p.departmentId() == null ? "" : " and " + alias + ".department_id = :dept";
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static Double round(Object o) {
        return o == null ? null : round1(((Number) o).doubleValue());
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static Double pct(long part, long whole) {
        return whole == 0 ? null : round1(part * 100.0 / whole);
    }

    private static String truncate(String v, int max) {
        return v == null ? null : v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }

    /** The default business calendar's time zone (as the other analytics). */
    ZoneId zone() {
        List<String> zones = jdbc.queryForList("select timezone from business_hours_calendars where is_default limit 1", new MapSqlParameterSource(),
                String.class);
        try {
            return zones.isEmpty() ? ZoneOffset.UTC : ZoneId.of(zones.get(0));
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}

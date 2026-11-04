package com.nexus360.report;

import com.nexus360.analytics.AnalyticsDtos.DepartmentRow;
import com.nexus360.analytics.AnalyticsDtos.Kpis;
import com.nexus360.analytics.AnalyticsDtos.Overview;
import com.nexus360.analytics.AnalyticsDtos.PriorityRow;
import com.nexus360.analytics.AnalyticsDtos.ServiceRow;
import com.nexus360.analytics.AnalyticsService;
import com.nexus360.common.Csv;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Release 3 report generators built on existing data sources: ANALYTICS_OVERVIEW (the analytics PDF, or its KPIs and
 * breakdowns as CSV), SLA (clocks completed in the period) and INCIDENTS (incidents created in the period).
 */
@Configuration
@RequiredArgsConstructor
class CoreReportGenerators {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AnalyticsService analytics;
    private final NamedParameterJdbcTemplate jdbc;

    @Bean
    ReportGenerator analyticsOverviewReport() {
        return new Generator(ReportType.ANALYTICS_OVERVIEW, "KPIs, priorities, services, departments and agents for the period", r -> {
            AnalyticsService.Period p = analytics.period(r.from(), r.to(), null, r.departmentId());
            if (r.format() == ReportFormat.PDF) {
                return analytics.overviewPdf(p);
            }
            Overview o = analytics.overview(p);
            Kpis k = o.kpis();
            List<List<Object>> rows = new ArrayList<>();
            rows.add(row("kpi", "Incidents created", "count", k.createdInPeriod()));
            rows.add(row("kpi", "Incidents resolved", "count", k.resolvedInPeriod()));
            rows.add(row("kpi", "Open now", "count", k.openIncidents()));
            rows.add(row("kpi", "Critical open now", "count", k.criticalOpen()));
            rows.add(row("kpi", "Resolution rate", "percent", k.resolutionRate()));
            rows.add(row("kpi", "First call resolution", "percent", k.firstCallResolution()));
            rows.add(row("kpi", "SLA compliance", "percent", k.slaCompliance()));
            rows.add(row("kpi", "Mean time to resolve", "minutes", k.mttrMinutes()));
            rows.add(row("kpi", "Mean time to acknowledge", "minutes", k.mttaMinutes()));
            for (PriorityRow pr : o.byPriority()) {
                rows.add(row("priority", pr.priority().name(), "created", pr.count()));
                rows.add(row("priority", pr.priority().name(), "mttr_minutes", pr.mttrMinutes()));
                rows.add(row("priority", pr.priority().name(), "sla_compliance_percent", pr.slaCompliance()));
            }
            for (ServiceRow s : o.byService()) {
                rows.add(row("service", s.name(), "created", s.created()));
                rows.add(row("service", s.name(), "resolved", s.resolved()));
                rows.add(row("service", s.name(), "open", s.open()));
                rows.add(row("service", s.name(), "mttr_minutes", s.mttrMinutes()));
            }
            for (DepartmentRow d : o.byDepartment()) {
                rows.add(row("department", d.name(), "open", d.open()));
                rows.add(row("department", d.name(), "resolved", d.resolved()));
                rows.add(row("department", d.name(), "mttr_minutes", d.mttrMinutes()));
                rows.add(row("department", d.name(), "sla_compliance_percent", d.slaCompliance()));
            }
            return Csv.bytes(List.of("section", "item", "metric", "value"), rows);
        });
    }

    @Bean
    ReportGenerator slaReport() {
        return new Generator(ReportType.SLA, "SLA clocks completed in the period: compliance by priority and department, and every miss", r -> {
            AnalyticsService.Period p = analytics.period(r.from(), r.to(), null, r.departmentId());
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("from", java.sql.Timestamp.from(p.from()))
                    .addValue("to", java.sql.Timestamp.from(p.to())).addValue("dept", p.departmentId());
            String dept = p.departmentId() == null ? "" : " and i.department_id = :dept";
            List<Map<String, Object>> clocks = jdbc.queryForList("select i.number, i.title, s.priority, s.policy_name, s.outcome, s.response_breached, "
                    + "s.started_at, s.responded_at, s.resolution_due_at, s.completed_at, d.name as department, i.is_demo "
                    + "from incident_slas s join incidents i on i.id = s.incident_id left join departments d on d.id = i.department_id "
                    + "where s.completed_at >= :from and s.completed_at < :to and s.outcome in ('MET', 'MISSED') and not i.simulated" + dept
                    + " order by s.completed_at, i.number", params);
            if (r.format() == ReportFormat.CSV) {
                List<List<Object>> rows = clocks.stream().map(c -> Arrays.<Object>asList(c.get("number"), c.get("title"), c.get("priority"),
                        c.get("policy_name"), c.get("outcome"), c.get("response_breached"), text(c.get("started_at")), text(c.get("responded_at")),
                        text(c.get("resolution_due_at")), text(c.get("completed_at")), c.get("department"), c.get("is_demo"))).toList();
                return Csv.bytes(List.of("number", "title", "priority", "policy", "outcome", "response_breached", "started_at", "responded_at",
                        "resolution_due_at", "completed_at", "department", "demo"), rows);
            }
            long met = clocks.stream().filter(c -> "MET".equals(c.get("outcome"))).count();
            long responseOk = clocks.stream().filter(c -> Boolean.FALSE.equals(c.get("response_breached"))).count();
            Map<String, long[]> byPriority = new LinkedHashMap<>();
            for (String pr : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
                byPriority.put(pr, new long[2]);
            }
            Map<String, long[]> byDepartment = new LinkedHashMap<>();
            for (Map<String, Object> c : clocks) {
                boolean ok = "MET".equals(c.get("outcome"));
                long[] a = byPriority.computeIfAbsent(String.valueOf(c.get("priority")), k -> new long[2]);
                a[ok ? 0 : 1]++;
                long[] d = byDepartment.computeIfAbsent(c.get("department") == null ? "Unassigned" : String.valueOf(c.get("department")), k -> new long[2]);
                d[ok ? 0 : 1]++;
            }
            List<ReportPdf.Table> tables = new ArrayList<>();
            tables.add(new ReportPdf.Table("Summary", List.of("Indicator", "Value"), new float[]{3, 2}, List.of(
                    List.of("SLA clocks completed", String.valueOf(clocks.size())),
                    List.of("Met", String.valueOf(met)),
                    List.of("Missed", String.valueOf(clocks.size() - met)),
                    List.of("Resolution compliance", percent(met, clocks.size())),
                    List.of("Response compliance", percent(responseOk, clocks.size()))), true));
            tables.add(new ReportPdf.Table("By priority", List.of("Priority", "Met", "Missed", "Compliance"), new float[]{3, 1, 1, 2},
                    byPriority.entrySet().stream().map(e -> List.of(e.getKey(), String.valueOf(e.getValue()[0]), String.valueOf(e.getValue()[1]),
                            percent(e.getValue()[0], e.getValue()[0] + e.getValue()[1]))).toList(), true));
            tables.add(new ReportPdf.Table("By department", List.of("Department", "Met", "Missed", "Compliance"), new float[]{3, 1, 1, 2},
                    byDepartment.entrySet().stream().map(e -> List.of(e.getKey(), String.valueOf(e.getValue()[0]), String.valueOf(e.getValue()[1]),
                            percent(e.getValue()[0], e.getValue()[0] + e.getValue()[1]))).toList(), true));
            tables.add(new ReportPdf.Table("Missed SLAs", List.of("Incident", "Title", "Priority", "Due", "Completed"), new float[]{2, 5, 2, 3, 3},
                    clocks.stream().filter(c -> "MISSED".equals(c.get("outcome"))).limit(200)
                            .map(c -> List.of(String.valueOf(c.get("number")), String.valueOf(c.get("title")), String.valueOf(c.get("priority")),
                                    str(c.get("resolution_due_at")), str(c.get("completed_at")))).toList(), false));
            return ReportPdf.render(r.title(), intro(r, p), tables);
        });
    }

    @Bean
    ReportGenerator incidentsReport() {
        return new Generator(ReportType.INCIDENTS, "Every incident created in the period with priority, status, service, assignee and SLA outcome", r -> {
            AnalyticsService.Period p = analytics.period(r.from(), r.to(), null, r.departmentId());
            List<List<Object>> rows = analytics.incidentRows(p);
            if (r.format() == ReportFormat.CSV) {
                return Csv.bytes(AnalyticsService.INCIDENT_CSV_HEADER, rows);
            }
            List<List<String>> table = rows.stream().limit(1000).map(x -> List.of(str(x.get(0)), str(x.get(1)), str(x.get(2)), str(x.get(3)),
                    str(x.get(5)), str(x.get(9)), str(x.get(11)))).toList();
            List<String> intro = new ArrayList<>(intro(r, p));
            intro.add(rows.size() + " incidents" + (rows.size() > 1000 ? " (first 1000 listed; the CSV format contains all)" : "") + ".");
            return ReportPdf.render(r.title(), intro, List.of(new ReportPdf.Table("Incidents", List.of("Number", "Title", "Priority", "Status",
                    "Service", "Created", "Resolved"), new float[]{2, 5, 2, 2, 3, 3, 3}, table, false)));
        });
    }

    private static List<String> intro(ReportGenerator.Request r, AnalyticsService.Period p) {
        return List.of("Period: " + r.from() + " to " + r.to() + " (" + r.zone().getId() + ")",
                "Filters: department = " + (p.departmentId() == null ? "all departments" : "#" + p.departmentId()),
                "Generated " + TIME.format(r.generatedAt().atZone(ZoneOffset.UTC)) + " UTC from live records.");
    }

    private static List<Object> row(String section, String item, String metric, Object value) {
        return Arrays.asList(section, item, metric, value);
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "n/a" : Math.round(part * 1000.0 / whole) / 10.0 + " %";
    }

    private static String text(Object o) {
        return o == null ? null : o.toString();
    }

    private static String str(Object o) {
        return o == null ? "-" : o.toString();
    }

    /** A generator from a type, a description and a function. */
    record Generator(ReportType type, String description, java.util.function.Function<ReportGenerator.Request, byte[]> body)
            implements ReportGenerator {

        @Override
        public byte[] generate(ReportGenerator.Request request) {
            return body.apply(request);
        }
    }
}

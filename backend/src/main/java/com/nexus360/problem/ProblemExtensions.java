package com.nexus360.problem;

import com.nexus360.common.Csv;
import com.nexus360.mywork.MyWorkContributor;
import com.nexus360.problem.ProblemDtos.ProblemAnalytics;
import com.nexus360.problem.ProblemDtos.ProblemSummary;
import com.nexus360.report.ReportFormat;
import com.nexus360.report.ReportGenerator;
import com.nexus360.report.ReportPdf;
import com.nexus360.report.ReportType;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Problem module contributions to the Release 3 extension points: My Work sections {@code problems} (active problems I
 * own, or unowned in my teams) and {@code correctiveActions} (open actions assigned to me), and the PROBLEMS report.
 */
@Configuration
@RequiredArgsConstructor
class ProblemExtensions {

    private final ProblemService problems;
    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;

    @Bean
    MyWorkContributor problemsSection() {
        return new MyWorkContributor() {
            @Override
            public String key() {
                return "problems";
            }

            @Override
            public int order() {
                return 110;
            }

            @Override
            public Object contribute(Context ctx) {
                return new Section<ProblemSummary>(problems.workCount(ctx.actor().id(), ctx.teamIds()),
                        problems.workFor(ctx.actor().id(), ctx.teamIds(), ctx.limit()));
            }
        };
    }

    @Bean
    MyWorkContributor correctiveActionsSection() {
        return new MyWorkContributor() {
            @Override
            public String key() {
                return "correctiveActions";
            }

            @Override
            public int order() {
                return 115;
            }

            @Override
            public Object contribute(Context ctx) {
                List<Map<String, Object>> rows = jdbc.queryForList("select a.id, a.title, a.action_type, a.due_date, a.status, p.id as problem_id, "
                        + "p.number as problem_number, p.title as problem_title from problem_actions a join problems p on p.id = a.problem_id "
                        + "where a.owner_id = ? and a.status in ('OPEN', 'IN_PROGRESS') order by a.due_date asc nulls last, a.id limit ?",
                        ctx.actor().id(), ctx.limit());
                Long total = jdbc.queryForObject("select count(*) from problem_actions where owner_id = ? and status in ('OPEN', 'IN_PROGRESS')",
                        Long.class, ctx.actor().id());
                LocalDate today = LocalDate.now(clock);
                List<Map<String, Object>> items = rows.stream().map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", r.get("id"));
                    m.put("title", r.get("title"));
                    m.put("actionType", r.get("action_type"));
                    LocalDate due = r.get("due_date") == null ? null : ((java.sql.Date) r.get("due_date")).toLocalDate();
                    m.put("dueDate", due == null ? null : due.toString());
                    m.put("overdue", due != null && due.isBefore(today));
                    m.put("status", r.get("status"));
                    m.put("problemId", r.get("problem_id"));
                    m.put("problemNumber", r.get("problem_number"));
                    m.put("problemTitle", r.get("problem_title"));
                    return m;
                }).toList();
                return new Section<>(total == null ? 0 : total, items);
            }
        };
    }

    @Bean
    ReportGenerator problemsReport() {
        return new ReportGenerator() {
            @Override
            public ReportType type() {
                return ReportType.PROBLEMS;
            }

            @Override
            public String description() {
                return "Problems created in the period with status, priority, known errors, linked incidents and open actions";
            }

            @Override
            public Set<String> filterKeys() {
                return Set.of("departmentId");
            }

            @Override
            public byte[] generate(Request r) {
                ZoneId zone = r.zone();
                Timestamp from = Timestamp.from(r.from().atStartOfDay(zone).toInstant());
                Timestamp to = Timestamp.from(r.to().plusDays(1).atStartOfDay(zone).toInstant());
                Long dept = r.departmentId();
                List<Map<String, Object>> rows = jdbc.queryForList("select p.number, p.title, p.status, p.priority, p.known_error, "
                        + "u.full_name as owner, t.name as team, p.created_at, p.root_cause_at, p.resolved_at, p.is_demo, "
                        + "(select count(*) from problem_incidents pi where pi.problem_id = p.id) as incidents, "
                        + "(select count(*) from problem_actions a where a.problem_id = p.id and a.status in ('OPEN', 'IN_PROGRESS')) as open_actions "
                        + "from problems p left join users u on u.id = p.owner_id left join teams t on t.id = p.team_id "
                        + "where p.created_at >= ? and p.created_at < ? and (cast(? as bigint) is null or p.department_id = ?) order by p.created_at",
                        from, to, dept, dept);
                if (r.format() == ReportFormat.CSV) {
                    List<List<Object>> out = rows.stream().map(x -> Arrays.<Object>asList(x.get("number"), x.get("title"), x.get("status"),
                            x.get("priority"), x.get("known_error"), x.get("owner"), x.get("team"), x.get("incidents"), x.get("open_actions"),
                            str(x.get("created_at")), str(x.get("root_cause_at")), str(x.get("resolved_at")), x.get("is_demo"))).toList();
                    return Csv.bytes(List.of("number", "title", "status", "priority", "known_error", "owner", "team", "linked_incidents",
                            "open_actions", "created_at", "root_cause_at", "resolved_at", "demo"), out);
                }
                ProblemAnalytics a = problems.analytics((int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(r.from(), r.to()) + 1));
                List<ReportPdf.Table> tables = new ArrayList<>();
                tables.add(new ReportPdf.Table("Summary", List.of("Indicator", "Value"), new float[]{3, 2}, List.of(
                        List.of("Problems created in the period", String.valueOf(rows.size())),
                        List.of("Open problems now", String.valueOf(a.open())),
                        List.of("Active known errors", String.valueOf(a.knownErrors())),
                        List.of("Average hours to root cause", a.avgHoursToRootCause() == null ? "n/a" : String.valueOf(a.avgHoursToRootCause())),
                        List.of("Average hours to resolve", a.avgHoursToResolve() == null ? "n/a" : String.valueOf(a.avgHoursToResolve())),
                        List.of("Overdue corrective actions", String.valueOf(a.overdueActions()))), true));
                tables.add(new ReportPdf.Table("Problems", List.of("Number", "Title", "Status", "Priority", "Incidents", "Open actions"),
                        new float[]{2, 6, 3, 2, 2, 2}, rows.stream().limit(500).map(x -> List.of(str(x.get("number")), str(x.get("title")),
                        str(x.get("status")), str(x.get("priority")), str(x.get("incidents")), str(x.get("open_actions")))).toList(), false));
                return ReportPdf.render(r.title(), List.of("Period: " + r.from() + " to " + r.to() + " (" + zone.getId() + ")",
                        "Filters: department = " + (dept == null ? "all departments" : "#" + dept)), tables);
            }
        };
    }

    private static String str(Object o) {
        return o == null ? "" : o instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(o);
    }
}

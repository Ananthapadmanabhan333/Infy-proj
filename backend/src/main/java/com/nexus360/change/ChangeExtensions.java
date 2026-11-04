package com.nexus360.change;

import com.nexus360.change.ChangeDtos.ChangeMetrics;
import com.nexus360.change.ChangeDtos.ChangeSummary;
import com.nexus360.common.Csv;
import com.nexus360.mywork.MyWorkContributor;
import com.nexus360.portal.PortalContributor;
import com.nexus360.report.ReportFormat;
import com.nexus360.report.ReportGenerator;
import com.nexus360.report.ReportPdf;
import com.nexus360.report.ReportType;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Change module contributions to the Release 3 extension points: My Work section {@code changes} (changes I implement or
 * raised that need work, and finished emergency changes waiting for their review), the employee-portal section
 * {@code plannedChanges} (approved changes in the next 14 days on business services: title, window and services only) and
 * the CHANGES report.
 */
@Configuration
@RequiredArgsConstructor
class ChangeExtensions {

    private final ChangeService changes;
    private final JdbcTemplate jdbc;

    @Bean
    MyWorkContributor changesSection() {
        return new MyWorkContributor() {
            @Override
            public String key() {
                return "changes";
            }

            @Override
            public int order() {
                return 120;
            }

            @Override
            public Object contribute(Context ctx) {
                return new Section<ChangeSummary>(changes.workCount(ctx.actor().id()), changes.workFor(ctx.actor().id(), ctx.limit()));
            }
        };
    }

    @Bean
    PortalContributor plannedChangesSection() {
        return new PortalContributor() {
            @Override
            public String key() {
                return "plannedChanges";
            }

            @Override
            public int order() {
                return 110;
            }

            @Override
            public Object contribute(Context ctx) {
                Timestamp now = Timestamp.from(ctx.now());
                Timestamp until = Timestamp.from(ctx.now().plus(Duration.ofDays(14)));
                List<Map<String, Object>> rows = jdbc.queryForList("select c.id, c.title, c.planned_start, c.planned_end, "
                        + "string_agg(s.name, ', ' order by s.name) as services from changes c join change_services cs on cs.change_id = c.id "
                        + "join business_services s on s.id = cs.service_id where c.status in ('APPROVED', 'SCHEDULED', 'IMPLEMENTING') "
                        + "and c.planned_end > ? and c.planned_start < ? group by c.id, c.title, c.planned_start, c.planned_end "
                        + "order by c.planned_start limit ?", now, until, ctx.limit());
                List<Map<String, Object>> items = rows.stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.get("id"));
                    m.put("title", r.get("title"));
                    m.put("startsAt", ((Timestamp) r.get("planned_start")).toInstant());
                    m.put("endsAt", ((Timestamp) r.get("planned_end")).toInstant());
                    m.put("services", r.get("services") == null ? List.of() : List.of(String.valueOf(r.get("services")).split(", ")));
                    return m;
                }).toList();
                return new MyWorkContributor.Section<>(items.size(), items);
            }
        };
    }

    @Bean
    ReportGenerator changesReport() {
        return new ReportGenerator() {
            @Override
            public ReportType type() {
                return ReportType.CHANGES;
            }

            @Override
            public String description() {
                return "Changes created in the period with type, risk, status, window and outcome; success and failure rates";
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
                List<Map<String, Object>> rows = jdbc.queryForList("select c.number, c.title, c.change_type, c.status, c.risk, c.risk_score, "
                        + "c.planned_start, c.planned_end, c.actual_start, c.actual_end, u.full_name as assignee, t.name as team, c.is_demo "
                        + "from changes c left join users u on u.id = c.assignee_id left join teams t on t.id = c.team_id "
                        + "where c.created_at >= ? and c.created_at < ? and (cast(? as bigint) is null or t.department_id = ?) order by c.created_at",
                        from, to, dept, dept);
                if (r.format() == ReportFormat.CSV) {
                    List<List<Object>> out = rows.stream().map(x -> Arrays.<Object>asList(x.get("number"), x.get("title"), x.get("change_type"),
                            x.get("status"), x.get("risk"), x.get("risk_score"), str(x.get("planned_start")), str(x.get("planned_end")),
                            str(x.get("actual_start")), str(x.get("actual_end")), x.get("assignee"), x.get("team"), x.get("is_demo"))).toList();
                    return Csv.bytes(List.of("number", "title", "type", "status", "risk", "risk_score", "planned_start", "planned_end", "actual_start",
                            "actual_end", "implementer", "team", "demo"), out);
                }
                ChangeMetrics m = changes.metrics((int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(r.from(), r.to()) + 1));
                List<ReportPdf.Table> tables = new ArrayList<>();
                tables.add(new ReportPdf.Table("Summary", List.of("Indicator", "Value"), new float[]{3, 2}, List.of(
                        List.of("Changes created in the period", String.valueOf(rows.size())),
                        List.of("Completed / failed / rolled back", m.completed() + " / " + m.failed() + " / " + m.rolledBack()),
                        List.of("Success rate", m.successRate() == null ? "n/a" : m.successRate() + " %"),
                        List.of("Emergency share", m.emergencyRate() == null ? "n/a" : m.emergencyRate() + " %"),
                        List.of("Average approval time (hours)", m.avgApprovalHours() == null ? "n/a" : String.valueOf(m.avgApprovalHours())),
                        List.of("Changes followed by incidents", String.valueOf(m.changesFollowedByIncidents()))), true));
                tables.add(new ReportPdf.Table("Changes", List.of("Number", "Title", "Type", "Risk", "Status", "Planned start"),
                        new float[]{2, 6, 2, 2, 3, 3}, rows.stream().limit(500).map(x -> List.of(str(x.get("number")), str(x.get("title")),
                        str(x.get("change_type")), str(x.get("risk")), str(x.get("status")), str(x.get("planned_start")))).toList(), false));
                return ReportPdf.render(r.title(), List.of("Period: " + r.from() + " to " + r.to() + " (" + zone.getId() + ")",
                        "Filters: department = " + (dept == null ? "all departments" : "#" + dept)), tables);
            }
        };
    }

    private static String str(Object o) {
        return o == null ? "" : o instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(o);
    }
}

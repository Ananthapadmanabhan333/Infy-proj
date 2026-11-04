package com.nexus360.oncall;

import com.nexus360.common.Csv;
import com.nexus360.mywork.MyWorkContributor;
import com.nexus360.oncall.OnCallDtos.MyShift;
import com.nexus360.oncall.OnCallDtos.NowEntry;
import com.nexus360.oncall.OnCallService.PerformanceRow;
import com.nexus360.oncall.Rotation.Assignment;
import com.nexus360.oncall.Rotation.Layer;
import com.nexus360.report.ReportFormat;
import com.nexus360.report.ReportGenerator;
import com.nexus360.report.ReportPdf;
import com.nexus360.report.ReportType;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * On-call contributions to the Release 3 extension points: the My Work section {@code onCallShifts} (my current and upcoming shifts
 * in the next 14 days) and the {@code ONCALL} scheduled report (computed shifts and hours per person, pages and acknowledgement
 * times in the period).
 */
@Configuration
@RequiredArgsConstructor
class OnCallExtensions {

    private static final int MAX_REPORT_DAYS = 62;

    private final OnCallService oncall;
    private final OnCallScheduleRepository schedules;
    private final UserRepository users;

    @Bean
    MyWorkContributor onCallShiftsSection() {
        return new MyWorkContributor() {
            @Override
            public String key() {
                return "onCallShifts";
            }

            @Override
            public int order() {
                return 140;
            }

            @Override
            public Object contribute(Context ctx) {
                List<MyShift> all = oncall.myShifts(ctx.actor().id(), 14, 500);
                return new Section<>(all.size(), all.stream().limit(ctx.limit()).toList());
            }
        };
    }

    @Bean
    ReportGenerator onCallReport() {
        return new ReportGenerator() {
            @Override
            public ReportType type() {
                return ReportType.ONCALL;
            }

            @Override
            public String description() {
                return "On-call shifts and hours per person, pages, acknowledgement times and escalations in the period";
            }

            @Override
            public byte[] generate(Request r) {
                ZoneId zone = r.zone();
                java.time.LocalDate first = r.to().minusDays(MAX_REPORT_DAYS - 1L).isAfter(r.from()) ? r.to().minusDays(MAX_REPORT_DAYS - 1L) : r.from();
                Instant from = first.atStartOfDay(zone).toInstant();
                Instant to = r.to().plusDays(1).atStartOfDay(zone).toInstant();
                Long dept = r.departmentId();
                List<OnCallSchedule> list = schedules.findAllByOrderByNameAsc().stream()
                        .filter(s -> dept == null || Objects.equals(s.getTeam().getDepartment().getId(), dept)).toList();
                List<Object[]> shifts = new ArrayList<>();
                Set<Long> ids = new HashSet<>();
                for (OnCallSchedule s : list) {
                    OnCallService.Loaded l = oncall.load(s, from, to);
                    for (Layer layer : Layer.values()) {
                        for (Assignment a : Rotation.shifts(l.spec(), l.overrides(), l.absences(), layer, from, to)) {
                            shifts.add(new Object[]{s, a});
                            ids.add(a.userId());
                        }
                    }
                }
                Map<Long, User> people = ids.isEmpty() ? Map.of() : users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
                if (r.format() == ReportFormat.CSV) {
                    List<List<Object>> rows = shifts.stream().map(x -> {
                        OnCallSchedule s = (OnCallSchedule) x[0];
                        Assignment a = (Assignment) x[1];
                        User u = people.get(a.userId());
                        return Arrays.<Object>asList(s.getName(), s.getTeam().getName(), a.layer(), u == null ? a.userId() : u.getFullName(),
                                u == null ? "" : u.getEmail(), a.start().toString(), a.end().toString(), hours(a), a.source(), a.holiday(), s.isDemo());
                    }).toList();
                    return Csv.bytes(List.of("schedule", "team", "layer", "person", "email", "start", "end", "hours", "source", "holiday", "demo"), rows);
                }
                Map<String, double[]> hoursByPerson = new LinkedHashMap<>();
                for (Object[] x : shifts) {
                    Assignment a = (Assignment) x[1];
                    User u = people.get(a.userId());
                    double[] h = hoursByPerson.computeIfAbsent(u == null ? String.valueOf(a.userId()) : u.getFullName(), k -> new double[2]);
                    h[a.layer() == Layer.PRIMARY ? 0 : 1] += hours(a);
                }
                List<PerformanceRow> perf = oncall.performance(from, to, dept);
                List<ReportPdf.Table> tables = new ArrayList<>();
                List<NowEntry> now = oncall.onCall(null, r.generatedAt() == null ? Instant.now() : r.generatedAt());
                tables.add(new ReportPdf.Table("Schedules", List.of("Schedule", "Team", "Rotation", "Time zone", "Primary now", "Secondary now"),
                        new float[]{4, 3, 2, 3, 3, 3}, list.stream().map(s -> {
                            NowEntry e = now.stream().filter(n -> n.scheduleId().equals(s.getId())).findFirst().orElse(null);
                            return List.of(s.getName(), s.getTeam().getName(), s.getRotationType().name(), s.getTimeZone(),
                                    e == null || e.primary() == null ? "-" : e.primary().name(), e == null || e.secondary() == null ? "-" : e.secondary().name());
                        }).toList(), false));
                tables.add(new ReportPdf.Table("Hours on call per person", List.of("Person", "Primary hours", "Secondary hours"), new float[]{4, 2, 2},
                        hoursByPerson.entrySet().stream().map(e -> List.of(e.getKey(), round(e.getValue()[0]), round(e.getValue()[1]))).toList(), true));
                tables.add(new ReportPdf.Table("Pages and acknowledgements", List.of("Schedule", "Alerts paged", "Pages", "Acknowledged",
                        "Avg minutes to ack", "Escalated (level 2+)"), new float[]{4, 2, 2, 2, 2, 2}, perf.stream().map(p -> List.of(
                        p.scheduleName() == null ? "(schedule removed)" : p.scheduleName(), String.valueOf(p.alertsPaged()), String.valueOf(p.pages()),
                        String.valueOf(p.acknowledged()), p.ackSamples() == 0 ? "n/a" : round(p.ackMinutesSum() / p.ackSamples()),
                        String.valueOf(p.escalated()))).toList(), true));
                List<String> intro = new ArrayList<>(List.of("Period: " + first + " to " + r.to() + " (" + zone.getId() + ")",
                        "Filters: department = " + (dept == null ? "all departments" : "#" + dept)));
                if (first.isAfter(r.from())) {
                    intro.add("Shifts are listed for the last " + MAX_REPORT_DAYS + " days of the period.");
                }
                return ReportPdf.render(r.title(), intro, tables);
            }
        };
    }

    private static double hours(Assignment a) {
        return Math.round(Duration.between(a.start(), a.end()).toMinutes() / 6.0) / 10.0;
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }
}

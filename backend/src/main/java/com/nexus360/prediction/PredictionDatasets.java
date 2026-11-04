package com.nexus360.prediction;

import com.nexus360.observability.TelemetryMaintenanceService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the stored history behind each prediction model and turns it into feature vectors. Training and serving use the same feature
 * code, so a served prediction sees exactly the features the model was trained on. Simulated incidents, alerts and telemetry are
 * excluded unless {@code includeSimulated}. Read-only.
 */
@Component
@RequiredArgsConstructor
public class PredictionDatasets {

    static final int MAX_ROWS = 20_000;
    static final String[] PRIORITIES = {"CRITICAL", "HIGH", "MEDIUM"};
    static final String[] CATEGORIES = {"APPLICATION", "NETWORK", "HARDWARE", "ACCESS", "DATABASE", "EMAIL", "SECURITY", "FACILITIES"};
    static final String[] RESOLUTIONS = {"FIXED", "WORKAROUND", "CHANGE_ROLLED_BACK", "NOT_REPRODUCIBLE", "USER_ERROR"};
    /** Elapsed shares of the resolution target at which a training sample is taken (only while the clock was still running). */
    static final double[] OBSERVATION_SHARES = {0.1, 0.3, 0.5, 0.7, 0.9};
    public static final int RECURRENCE_DAYS = 14;
    /** Utilisation metrics considered by CAPACITY_RISK (series name, case-insensitive). */
    public static final String CAPACITY_PATTERN = "(cpu|memory|mem\\.|disk|filesystem|storage|connections|pool|utiliz|utilis)";
    public static final int SEASON = 7;

    public static final List<String> SLA_FEATURES;
    public static final List<String> RECURRENCE_FEATURES;

    static {
        List<String> sla = new ArrayList<>();
        for (String p : PRIORITIES) {
            sla.add("priority_" + p.toLowerCase(Locale.ROOT));
        }
        for (String c : CATEGORIES) {
            sla.add("category_" + c.toLowerCase(Locale.ROOT));
        }
        sla.addAll(List.of("service_tier", "team_load_log", "hour_sin", "hour_cos", "elapsed_share", "reassignments_log"));
        SLA_FEATURES = List.copyOf(sla);
        List<String> rec = new ArrayList<>(List.of("past_recurrence_log", "problem_linked"));
        for (String r : RESOLUTIONS) {
            rec.add("resolution_" + r.toLowerCase(Locale.ROOT));
        }
        rec.add("change_since");
        for (String p : PRIORITIES) {
            rec.add("priority_" + p.toLowerCase(Locale.ROOT));
        }
        rec.add("service_tier");
        RECURRENCE_FEATURES = List.copyOf(rec);
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TelemetryMaintenanceService maintenance;

    /** One training sample: subject id, the time that orders it for the time-based split, feature vector and label. */
    public record Sample(long subjectId, Instant at, double[] x, int y) {}

    // ------------------------------------------------------------------ SLA_BREACH

    /** A completed resolution clock with the facts known at incident creation. */
    public record SlaIncident(long id, Instant createdAt, Instant completedAt, String priority, String category, int tier, int teamLoad,
                              double finalShare, boolean breached, List<Instant> assigneeChanges) {}

    private static final String TEAM_LOAD = "(select count(*) from incidents o where o.department_id = i.department_id and o.parent_id is null "
            + "and o.id <> i.id and o.created_at <= i.created_at and o.created_at > i.created_at - interval '30 days' "
            + "and (o.resolved_at is null or o.resolved_at > i.created_at)%s)";

    public List<SlaIncident> slaIncidents(Instant from, Instant to, boolean includeSimulated) {
        String sim = includeSimulated ? "" : " and not i.simulated";
        String load = String.format(TEAM_LOAD, includeSimulated ? "" : " and not o.simulated");
        List<Object[]> rows = jdbc.query("select i.id, i.created_at, s.completed_at, i.priority, i.category, coalesce(bs.tier, 0) as tier, " + load
                + " as team_load, s.consumed_seconds, s.resolution_target_minutes, s.outcome from incidents i join incident_slas s on s.incident_id = i.id "
                + "left join business_services bs on bs.id = i.service_id where i.parent_id is null and s.outcome in ('MET', 'MISSED') "
                + "and s.resolution_target_minutes > 0 and i.created_at >= :from and i.created_at < :to and s.completed_at < :to" + sim
                + " order by i.created_at, i.id limit " + MAX_ROWS,
                new MapSqlParameterSource("from", at(from)).addValue("to", at(to)), (rs, n) -> new Object[]{rs.getLong(1), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
                        rs.getLong(8) / (rs.getInt(9) * 60.0), "MISSED".equals(rs.getString(10))});
        Map<Long, List<Instant>> changes = assigneeChanges(rows.stream().map(r -> (Long) r[0]).toList());
        return rows.stream().map(r -> new SlaIncident((Long) r[0], (Instant) r[1], (Instant) r[2], (String) r[3], (String) r[4], (Integer) r[5],
                (Integer) r[6], (Double) r[7], (Boolean) r[8], changes.getOrDefault((Long) r[0], List.of()))).toList();
    }

    /** Samples at each observation share s the clock was still running at (s below the final share); label = the clock breached. */
    public static List<Sample> slaSamples(List<SlaIncident> incidents) {
        List<Sample> out = new ArrayList<>();
        for (SlaIncident i : incidents) {
            for (double s : OBSERVATION_SHARES) {
                if (s >= i.finalShare()) {
                    break;
                }
                // Wall-clock time at elapsed share s, assuming business time ran evenly between creation and completion.
                long span = Math.max(0, Duration.between(i.createdAt(), i.completedAt()).toSeconds());
                Instant observed = i.createdAt().plusSeconds((long) (span * Math.min(1, s / Math.max(i.finalShare(), 1e-9))));
                int reassignments = reassignments(i.assigneeChanges(), observed);
                out.add(new Sample(i.id(), i.createdAt(), slaFeatures(i.priority(), i.category(), i.tier(), i.teamLoad(), i.createdAt(), s, reassignments),
                        i.breached() ? 1 : 0));
            }
        }
        return out;
    }

    static int reassignments(List<Instant> changes, Instant until) {
        long n = changes.stream().filter(c -> !c.isAfter(until)).count();
        return (int) Math.max(0, n - 1);
    }

    public static double[] slaFeatures(String priority, String category, int tier, int teamLoad, Instant createdAt, double share, int reassignments) {
        double[] x = new double[SLA_FEATURES.size()];
        int k = 0;
        for (String p : PRIORITIES) {
            x[k++] = p.equals(priority) ? 1 : 0;
        }
        for (String c : CATEGORIES) {
            x[k++] = c.equals(category) ? 1 : 0;
        }
        x[k++] = tier;
        x[k++] = Math.log1p(Math.max(0, teamLoad));
        double hour = createdAt.atOffset(ZoneOffset.UTC).getHour() + createdAt.atOffset(ZoneOffset.UTC).getMinute() / 60.0;
        x[k++] = Math.sin(2 * Math.PI * hour / 24);
        x[k++] = Math.cos(2 * Math.PI * hour / 24);
        x[k++] = Math.max(0, Math.min(1.5, share));
        x[k] = Math.log1p(Math.max(0, reassignments));
        return x;
    }

    /** Facts of one incident needed to serve an SLA_BREACH prediction now (null when the incident does not exist). */
    public record SlaSubject(long id, String number, String title, Instant createdAt, String priority, String category, int tier, int teamLoad,
                             int reassignments, boolean simulated, boolean topLevel, String status) {}

    public SlaSubject slaSubject(long incidentId, boolean includeSimulated) {
        String load = String.format(TEAM_LOAD, includeSimulated ? "" : " and not o.simulated");
        List<SlaSubject> list = jdbc.query("select i.id, i.number, i.title, i.created_at, i.priority, i.category, coalesce(bs.tier, 0), " + load
                + ", i.simulated, i.parent_id is null, i.status from incidents i left join business_services bs on bs.id = i.service_id where i.id = :id",
                new MapSqlParameterSource("id", incidentId), (rs, n) -> new SlaSubject(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8), 0, rs.getBoolean(9), rs.getBoolean(10),
                        rs.getString(11)));
        if (list.isEmpty()) {
            return null;
        }
        SlaSubject s = list.get(0);
        int reassignments = reassignments(assigneeChanges(List.of(incidentId)).getOrDefault(incidentId, List.of()), Instant.MAX);
        return new SlaSubject(s.id(), s.number(), s.title(), s.createdAt(), s.priority(), s.category(), s.tier(), s.teamLoad(), reassignments, s.simulated(),
                s.topLevel(), s.status());
    }

    private Map<Long, List<Instant>> assigneeChanges(List<Long> ids) {
        Map<Long, List<Instant>> out = new HashMap<>();
        for (int i = 0; i < ids.size(); i += 1000) {
            List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + 1000));
            jdbc.query("select incident_id, created_at from incident_history where field = 'assignee' and new_value is not null and incident_id in (:ids) "
                    + "order by created_at", new MapSqlParameterSource("ids", chunk), rs -> {
                out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getTimestamp(2).toInstant());
            });
        }
        return out;
    }

    // ------------------------------------------------------------------ INCIDENT_RECURRENCE

    public record RecurrenceRow(long id, String number, String title, Instant createdAt, Instant resolvedAt, String priority, String resolutionCode,
                                int tier, int pastRecurrence, boolean problemLinked, boolean changeSince, boolean recurred, boolean simulated,
                                String status, boolean topLevel, Long serviceId) {}

    private String recurrenceSelect(boolean includeSimulated) {
        String simO = includeSimulated ? "" : " and not o.simulated";
        String simR = includeSimulated ? "" : " and not r.simulated";
        return "select i.id, i.number, i.title, i.created_at, i.resolved_at, i.priority, i.resolution_code, coalesce(bs.tier, 0) as tier, "
                + "(select count(*) from incidents o where o.service_id = i.service_id and o.category = i.category and o.parent_id is null and o.id <> i.id "
                + "  and o.created_at >= i.created_at - interval '30 days' and o.created_at < i.created_at" + simO + ") as past, "
                + "exists (select 1 from problem_incidents pi where pi.incident_id = i.id) as problem_linked, "
                + "exists (select 1 from change_services cs join changes c on c.id = cs.change_id where cs.service_id = i.service_id "
                + "  and coalesce(c.actual_end, c.closed_at, c.planned_end) >= i.created_at "
                + "  and coalesce(c.actual_end, c.closed_at, c.planned_end) <= coalesce(i.resolved_at, :now)) as change_since, "
                + "exists (select 1 from incidents r where r.service_id = i.service_id and r.category = i.category and r.parent_id is null and r.id <> i.id "
                + "  and r.created_at > i.resolved_at and r.created_at <= i.resolved_at + interval '" + RECURRENCE_DAYS + " days'" + simR + ") as recurred, "
                + "i.simulated, i.status, i.parent_id is null as top_level, i.service_id "
                + "from incidents i left join business_services bs on bs.id = i.service_id ";
    }

    private static RecurrenceRow recurrenceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp resolved = rs.getTimestamp(5);
        return new RecurrenceRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), resolved == null ? null : resolved.toInstant(),
                rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getBoolean(10), rs.getBoolean(11), rs.getBoolean(12), rs.getBoolean(13),
                rs.getString(14), rs.getBoolean(15), (Long) rs.getObject(16));
    }

    /** Resolved incidents whose 14-day recurrence window closed before {@code to}. */
    public List<RecurrenceRow> recurrenceRows(Instant from, Instant to, Instant now, boolean includeSimulated) {
        Instant closedBy = to.minus(Duration.ofDays(RECURRENCE_DAYS));
        return jdbc.query(recurrenceSelect(includeSimulated) + "where i.parent_id is null and i.service_id is not null and i.resolved_at is not null "
                + "and coalesce(i.resolution_code, '') <> 'CANCELLED' and i.resolved_at >= :from and i.resolved_at < :closedBy"
                + (includeSimulated ? "" : " and not i.simulated") + " order by i.resolved_at, i.id limit " + MAX_ROWS,
                new MapSqlParameterSource("from", at(from)).addValue("closedBy", at(closedBy)).addValue("now", at(now)), (rs, n) -> recurrenceRow(rs));
    }

    public RecurrenceRow recurrenceSubject(long incidentId, Instant now, boolean includeSimulated) {
        List<RecurrenceRow> rows = jdbc.query(recurrenceSelect(includeSimulated) + "where i.id = :id",
                new MapSqlParameterSource("id", incidentId).addValue("now", at(now)), (rs, n) -> recurrenceRow(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static double[] recurrenceFeatures(RecurrenceRow r) {
        double[] x = new double[RECURRENCE_FEATURES.size()];
        int k = 0;
        x[k++] = Math.log1p(r.pastRecurrence());
        x[k++] = r.problemLinked() ? 1 : 0;
        for (String code : RESOLUTIONS) {
            x[k++] = code.equals(r.resolutionCode()) ? 1 : 0;
        }
        x[k++] = r.changeSince() ? 1 : 0;
        for (String p : PRIORITIES) {
            x[k++] = p.equals(r.priority()) ? 1 : 0;
        }
        x[k] = r.tier();
        return x;
    }

    public static List<Sample> recurrenceSamples(List<RecurrenceRow> rows) {
        return rows.stream().map(r -> new Sample(r.id(), r.resolvedAt(), recurrenceFeatures(r), r.recurred() ? 1 : 0)).toList();
    }

    // ------------------------------------------------------------------ CAPACITY_RISK

    public record SeriesInfo(long id, String name, String attributes, boolean simulated, long resourceId, String resourceServiceName, Long serviceId,
                             Long ciId) {}

    /** Hourly averages of one series: {@code start} is the first hour, {@code values[i]} the hour start + i (gaps carried forward). */
    public record Hourly(SeriesInfo series, Instant start, double[] values, int observed) {

        public Instant end() {
            return start.plus(Duration.ofHours(values.length));
        }
    }

    public List<SeriesInfo> capacitySeries(Instant from, Instant to, boolean includeSimulated, Long seriesId, Long serviceId, Long ciId, String metric) {
        maintenance.processPending(from, to);
        StringBuilder where = new StringBuilder(" where s.last_seen_at >= :from and s.first_seen_at < :to");
        MapSqlParameterSource p = new MapSqlParameterSource("from", at(from)).addValue("to", at(to));
        if (seriesId != null) {
            where.append(" and s.id = :sid");
            p.addValue("sid", seriesId);
        } else {
            where.append(" and s.name ~* :pattern");
            p.addValue("pattern", CAPACITY_PATTERN);
        }
        if (!includeSimulated) {
            where.append(" and not s.simulated");
        }
        if (serviceId != null) {
            where.append(" and r.service_id = :svc");
            p.addValue("svc", serviceId);
        }
        if (ciId != null) {
            where.append(" and r.ci_id = :ci");
            p.addValue("ci", ciId);
        }
        if (metric != null && !metric.isBlank()) {
            where.append(" and s.name = :metric");
            p.addValue("metric", metric.trim());
        }
        return jdbc.query("select s.id, s.name, s.attributes::text, s.simulated, r.id, r.service_name, r.service_id, r.ci_id from telemetry_metric_series s "
                + "join telemetry_resources r on r.id = s.resource_id" + where + " order by s.id limit 500", p, (rs, n) -> new SeriesInfo(rs.getLong(1),
                rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getLong(5), rs.getString(6), (Long) rs.getObject(7), (Long) rs.getObject(8)));
    }

    public List<Hourly> hourly(List<SeriesInfo> series, Instant from, Instant to) {
        if (series.isEmpty()) {
            return List.of();
        }
        Map<Long, TreeMap<Instant, Double>> values = new LinkedHashMap<>();
        Map<Long, SeriesInfo> byId = new LinkedHashMap<>();
        series.forEach(s -> {
            byId.put(s.id(), s);
            values.put(s.id(), new TreeMap<>());
        });
        jdbc.query("select series_id, date_trunc('hour', bucket_start) as h, avg(avg_value) from telemetry_metric_rollups where series_id in (:ids) "
                + "and bucket_start >= :from and bucket_start < :to group by 1, 2", new MapSqlParameterSource("ids", byId.keySet()).addValue("from", at(from))
                .addValue("to", at(to)), rs -> {
            values.get(rs.getLong(1)).put(rs.getTimestamp(2).toInstant(), rs.getDouble(3));
        });
        List<Hourly> out = new ArrayList<>();
        values.forEach((id, map) -> {
            if (map.isEmpty()) {
                return;
            }
            Instant first = map.firstKey();
            Instant last = map.lastKey();
            int n = (int) Duration.between(first, last).toHours() + 1;
            double[] grid = new double[n];
            double prev = map.firstEntry().getValue();
            for (int i = 0; i < n; i++) {
                Double v = map.get(first.plus(Duration.ofHours(i)));
                prev = v == null ? prev : v;
                grid[i] = prev;
            }
            out.add(new Hourly(byId.get(id), first, grid, map.size()));
        });
        return out;
    }

    /** Maximum observed value of a series in the window (for default thresholds). */
    public Double maxValue(long seriesId, Instant from) {
        return jdbc.queryForObject("select max(max_value) from telemetry_metric_rollups where series_id = :id and bucket_start >= :from",
                new MapSqlParameterSource("id", seriesId).addValue("from", at(from)), Double.class);
    }

    /** Latest value of the {@code <metric>.max} (or {@code .limit}) sibling series on the same resource, e.g. a connection pool size. */
    public Double limitFor(SeriesInfo s, Instant from) {
        String base = s.name().replaceAll("\\.(usage|used|utilization)$", "");
        List<Double> v = jdbc.queryForList("select r.avg_value from telemetry_metric_series x join telemetry_metric_rollups r on r.series_id = x.id "
                + "where x.resource_id = :res and x.name in (:names) and r.bucket_start >= :from order by r.bucket_start desc limit 1",
                new MapSqlParameterSource("res", s.resourceId()).addValue("names", List.of(base + ".max", base + ".limit", s.name() + ".max"))
                        .addValue("from", at(from)), Double.class);
        return v.isEmpty() ? null : v.get(0);
    }

    // ------------------------------------------------------------------ SERVICE_HEALTH

    public record DailySeries(long serviceId, String metric, Instant start, double[] values) {}

    /** Daily (UTC) counts of top-level incidents and of alerts per service, zero-filled from the service's first event day to {@code to}. */
    public List<DailySeries> dailyCounts(Instant from, Instant to, boolean includeSimulated, Long serviceId) {
        Instant dayFrom = from.truncatedTo(ChronoUnit.DAYS);
        Instant dayTo = to.truncatedTo(ChronoUnit.DAYS);
        MapSqlParameterSource p = new MapSqlParameterSource("from", at(dayFrom)).addValue("to", at(dayTo)).addValue("svc", serviceId);
        String svc = serviceId == null ? "" : " and service_id = :svc";
        Map<String, TreeMap<Instant, Double>> counts = new LinkedHashMap<>();
        jdbc.query("select service_id, date_trunc('day', created_at at time zone 'UTC') as d, count(*) from incidents where parent_id is null "
                + "and service_id is not null and created_at >= :from and created_at < :to" + svc + (includeSimulated ? "" : " and not simulated")
                + " group by 1, 2", p, rs -> {
            counts.computeIfAbsent(rs.getLong(1) + "|incidents", k -> new TreeMap<>()).put(day(rs.getTimestamp(2)), rs.getDouble(3));
        });
        jdbc.query("select service_id, date_trunc('day', first_seen_at at time zone 'UTC') as d, count(*) from alerts where service_id is not null "
                + "and first_seen_at >= :from and first_seen_at < :to" + svc + (includeSimulated ? "" : " and not simulated") + " group by 1, 2", p, rs -> {
            counts.computeIfAbsent(rs.getLong(1) + "|alerts", k -> new TreeMap<>()).put(day(rs.getTimestamp(2)), rs.getDouble(3));
        });
        List<DailySeries> out = new ArrayList<>();
        counts.forEach((key, map) -> {
            String[] parts = key.split("\\|");
            out.add(zeroFill(Long.parseLong(parts[0]), parts[1], map, map.firstKey(), dayTo));
        });
        return out;
    }

    /** The service's daily incident and alert counts over [from, to) zero-filled from {@code from} (serving: a fixed history window). */
    public List<DailySeries> serviceHistory(long serviceId, Instant from, Instant to, boolean includeSimulated) {
        List<DailySeries> raw = dailyCounts(from, to, includeSimulated, serviceId);
        Instant dayFrom = from.truncatedTo(ChronoUnit.DAYS);
        Instant dayTo = to.truncatedTo(ChronoUnit.DAYS);
        List<DailySeries> out = new ArrayList<>();
        for (String metric : List.of("incidents", "alerts")) {
            TreeMap<Instant, Double> map = new TreeMap<>();
            raw.stream().filter(d -> d.metric().equals(metric)).findFirst().ifPresent(d -> {
                for (int i = 0; i < d.values().length; i++) {
                    map.put(d.start().plus(Duration.ofDays(i)), d.values()[i]);
                }
            });
            out.add(zeroFill(serviceId, metric, map, dayFrom, dayTo));
        }
        TreeMap<Instant, Double> errors = new TreeMap<>();
        jdbc.query("select date_trunc('day', s.start_time at time zone 'UTC') as d, count(*), count(*) filter (where s.status_code = 'ERROR') "
                + "from telemetry_spans s join telemetry_resources r on r.id = s.resource_id where r.service_id = :svc and s.kind = 'SERVER' "
                + "and s.start_time >= :from and s.start_time < :to" + (includeSimulated ? "" : " and not s.simulated") + " group by 1",
                new MapSqlParameterSource("svc", serviceId).addValue("from", at(dayFrom)).addValue("to", at(dayTo)), rs -> {
                    long total = rs.getLong(2);
                    if (total > 0) {
                        errors.put(day(rs.getTimestamp(1)), rs.getLong(3) * 100.0 / total);
                    }
                });
        if (!errors.isEmpty()) {
            out.add(zeroFill(serviceId, "errorRatePercent", errors, errors.firstKey(), dayTo));
        }
        return out;
    }

    /** Actual daily count of a metric for one service and UTC day (outcome recording). */
    public double actualDaily(long serviceId, String metric, Instant day, boolean includeSimulated) {
        Instant end = day.plus(Duration.ofDays(1));
        MapSqlParameterSource p = new MapSqlParameterSource("svc", serviceId).addValue("from", at(day)).addValue("to", at(end));
        Long n = "alerts".equals(metric)
                ? jdbc.queryForObject("select count(*) from alerts where service_id = :svc and first_seen_at >= :from and first_seen_at < :to"
                        + (includeSimulated ? "" : " and not simulated"), p, Long.class)
                : jdbc.queryForObject("select count(*) from incidents where service_id = :svc and parent_id is null and created_at >= :from and created_at < :to"
                        + (includeSimulated ? "" : " and not simulated"), p, Long.class);
        return n == null ? 0 : n;
    }

    /** Actual hourly average of a series at {@code hour} (null until roll-ups exist). */
    public Double actualHourly(long seriesId, Instant hour) {
        return jdbc.queryForObject("select avg(avg_value) from telemetry_metric_rollups where series_id = :id and bucket_start >= :from and bucket_start < :to",
                new MapSqlParameterSource("id", seriesId).addValue("from", at(hour)).addValue("to", at(hour.plus(Duration.ofHours(1)))), Double.class);
    }

    private static DailySeries zeroFill(long serviceId, String metric, TreeMap<Instant, Double> map, Instant first, Instant dayTo) {
        int n = (int) Math.max(0, Duration.between(first, dayTo).toDays());
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = map.getOrDefault(first.plus(Duration.ofDays(i)), 0.0);
        }
        return new DailySeries(serviceId, metric, first, v);
    }

    private static Instant day(Timestamp ts) {
        // date_trunc(... at time zone 'UTC') yields a timestamp without time zone; read it as UTC.
        return ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

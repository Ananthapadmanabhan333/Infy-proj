package com.nexus360.observability;

import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.observability.ObservabilityDtos.LogDto;
import com.nexus360.observability.ObservabilityDtos.MetricNameDto;
import com.nexus360.observability.ObservabilityDtos.PointDto;
import com.nexus360.observability.ObservabilityDtos.RedSummary;
import com.nexus360.observability.ObservabilityDtos.ResourceChangeDto;
import com.nexus360.observability.ObservabilityDtos.ResourceDto;
import com.nexus360.observability.ObservabilityDtos.SeriesDto;
import com.nexus360.observability.ObservabilityDtos.SeriesResponse;
import com.nexus360.observability.ObservabilityDtos.SpanNode;
import com.nexus360.observability.ObservabilityDtos.TraceDetail;
import com.nexus360.observability.ObservabilityDtos.TraceSummary;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of SA Observability: metric catalogue and series (aggregated from the minute roll-ups), log search, traces with span trees,
 * per-service RED summaries, resources and their tracked attribute changes. Simulated data is excluded unless
 * {@code includeSimulated=true}; every response says whether it contains simulated data.
 */
@Service
@RequiredArgsConstructor
public class TelemetryQueryService {

    public static final Set<String> AGGREGATIONS = Set.of("avg", "max", "min", "sum", "p95");
    static final List<String> SEVERITIES = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");
    static final List<String> DURATION_METRICS = List.of("http.server.request.duration", "http.server.duration");
    private static final Pattern ATTR_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_.\\-]{0,119}$");
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final int MAX_SERIES = 50;
    private static final int MAX_POINTS_PER_SERIES = 1500;
    private static final int MAX_TRACE_SPANS = 5000;

    public record SeriesQuery(String name, Long serviceId, Long ciId, Long resourceId, Map<String, String> attributes, Instant from, Instant to,
                              Integer stepSeconds, String aggregation, boolean includeSimulated) {}

    public record LogQuery(String text, String severity, Long serviceId, Long resourceId, String traceId, Instant from, Instant to,
                           boolean includeSimulated) {}

    public record TraceQuery(Long serviceId, String name, Double minDurationMs, Boolean error, Instant from, Instant to, Integer limit,
                             boolean includeSimulated) {}

    private final JdbcTemplate jdbc;
    private final TelemetryMaintenanceService maintenance;
    private final Clock clock;

    // ------------------------------------------------------------------ metrics

    @Transactional(readOnly = true)
    public List<MetricNameDto> metricNames(String search, Long serviceId, boolean includeSimulated) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where true");
        if (!includeSimulated) {
            where.append(" and not s.simulated");
        }
        if (search != null && !search.isBlank()) {
            where.append(" and lower(s.name) like ? escape '\\'");
            args.add("%" + like(search.trim().toLowerCase(Locale.ROOT)) + "%");
        }
        if (serviceId != null) {
            where.append(" and r.service_id = ?");
            args.add(serviceId);
        }
        return jdbc.query("select s.name, min(s.metric_type), max(s.unit), count(*), max(s.last_seen_at), bool_or(s.simulated) from telemetry_metric_series s "
                + "join telemetry_resources r on r.id = s.resource_id" + where + " group by s.name order by s.name limit 500", (rs, n) -> new MetricNameDto(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), MappingRuleService.instant(rs, 5), rs.getBoolean(6)), args.toArray());
    }

    public SeriesResponse series(SeriesQuery q) {
        if (q.name() == null || q.name().isBlank()) {
            throw ApiException.invalidField("name", "name (metric name) is required");
        }
        String aggregation = q.aggregation() == null ? "avg" : q.aggregation().trim().toLowerCase(Locale.ROOT);
        if (!AGGREGATIONS.contains(aggregation)) {
            throw ApiException.invalidField("aggregation", "aggregation must be avg, max, min, sum or p95");
        }
        Instant to = q.to() == null ? clock.instant() : q.to();
        Instant from = q.from() == null ? to.minus(Duration.ofHours(1)) : q.from();
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("from must be before to");
        }
        long rangeSeconds = Duration.between(from, to).toSeconds();
        long step = q.stepSeconds() == null ? Math.max(60, ((rangeSeconds / 300) / 60) * 60) : q.stepSeconds();
        if (step < 60 || step % 60 != 0 || step > 86_400) {
            throw ApiException.invalidField("step", "step must be a multiple of 60 seconds between 60 and 86400");
        }
        if (rangeSeconds / step > MAX_POINTS_PER_SERIES) {
            throw ApiException.invalidField("step", "Too many points: use a larger step (at most " + MAX_POINTS_PER_SERIES + " points per series)");
        }
        maintenance.processPending(from, to);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where s.name = ?");
        args.add(q.name().trim());
        if (!q.includeSimulated()) {
            where.append(" and not s.simulated");
        }
        if (q.serviceId() != null) {
            where.append(" and r.service_id = ?");
            args.add(q.serviceId());
        }
        if (q.ciId() != null) {
            where.append(" and r.ci_id = ?");
            args.add(q.ciId());
        }
        if (q.resourceId() != null) {
            where.append(" and r.id = ?");
            args.add(q.resourceId());
        }
        if (q.attributes() != null && !q.attributes().isEmpty()) {
            for (String k : q.attributes().keySet()) {
                if (!ATTR_KEY.matcher(k).matches()) {
                    throw ApiException.badRequest("Invalid attribute filter key '" + OtlpJsonParser.truncate(k, 40) + "'");
                }
            }
            where.append(" and s.attributes @> ?::jsonb");
            args.add(TelemetryJson.write(q.attributes()));
        }
        record SeriesRow(long id, String type, String unit, Map<String, String> attributes, long resourceId, String serviceName, Long serviceId, Long ciId,
                         boolean simulated) {}
        args.add(MAX_SERIES);
        List<SeriesRow> rows = jdbc.query("select s.id, s.metric_type, s.unit, s.attributes::text, r.id, r.service_name, r.service_id, r.ci_id, s.simulated "
                + "from telemetry_metric_series s join telemetry_resources r on r.id = s.resource_id" + where + " order by s.id limit ?", (rs, n) -> new SeriesRow(
                rs.getLong(1), rs.getString(2), rs.getString(3), TelemetryJson.stringMap(rs.getString(4)), rs.getLong(5), rs.getString(6),
                MappingRuleService.nullableLong(rs, 7), MappingRuleService.nullableLong(rs, 8), rs.getBoolean(9)), args.toArray());
        List<SeriesDto> out = new ArrayList<>();
        boolean anySimulated = false;
        for (SeriesRow row : rows) {
            List<PointDto> points = points(row.id(), from, to, step, aggregation);
            if (points.isEmpty()) {
                continue;
            }
            anySimulated |= row.simulated();
            out.add(new SeriesDto(row.id(), q.name().trim(), row.type(), row.unit(), row.attributes(), row.resourceId(), row.serviceName(), row.serviceId(),
                    row.ciId(), row.simulated(), points));
        }
        return new SeriesResponse(q.name().trim(), aggregation, from, to, step, q.includeSimulated(), anySimulated, out.isEmpty() ? "NO_DATA" : "OK",
                out.isEmpty() ? "No data points for this metric and filter in the window" + (q.includeSimulated() ? "" : " (simulated data excluded)") : null,
                out);
    }

    /** Aggregated points of one series from its minute roll-ups. */
    public List<PointDto> points(long seriesId, Instant from, Instant to, long stepSeconds, String aggregation) {
        String value = switch (aggregation) {
            case "max" -> "max(max_value)";
            case "min" -> "min(min_value)";
            case "sum" -> "sum(sum_value)";
            case "p95" -> "percentile_cont(0.95) within group (order by p95_value)";
            default -> "sum(sum_value) / sum(sample_count)";
        };
        return jdbc.query("select to_timestamp(floor(extract(epoch from bucket_start) / ?) * ?) as b, " + value + ", sum(sample_count) from "
                + "telemetry_metric_rollups where series_id = ? and bucket_start >= ? and bucket_start < ? group by b order by b", (rs, n) -> new PointDto(
                rs.getTimestamp(1).toInstant(), rs.getDouble(2), rs.getLong(3)), stepSeconds, stepSeconds, seriesId, Timestamp.from(from), Timestamp.from(to));
    }

    // ------------------------------------------------------------------ logs

    @Transactional(readOnly = true)
    public PageResponse<LogDto> logs(LogQuery q, Integer page, Integer size) {
        Instant to = q.to() == null ? clock.instant() : q.to();
        Instant from = q.from() == null ? to.minus(Duration.ofHours(1)) : q.from();
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("from must be before to");
        }
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where l.ts >= ? and l.ts < ?");
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        if (!q.includeSimulated()) {
            where.append(" and not l.simulated");
        }
        if (q.text() != null && !q.text().isBlank()) {
            if (q.text().length() > 200) {
                throw ApiException.invalidField("text", "Search text must be at most 200 characters");
            }
            where.append(" and l.body ilike ? escape '\\'");
            args.add("%" + like(q.text().trim()) + "%");
        }
        if (q.severity() != null && !q.severity().isBlank()) {
            String sev = q.severity().trim().toUpperCase(Locale.ROOT);
            int index = SEVERITIES.indexOf(sev);
            if (index < 0) {
                throw ApiException.invalidField("severity", "severity must be TRACE, DEBUG, INFO, WARN, ERROR or FATAL (minimum severity)");
            }
            where.append(" and l.severity in (").append(String.join(",", SEVERITIES.subList(index, SEVERITIES.size()).stream().map(s -> "'" + s + "'")
                    .toList())).append(")");
        }
        if (q.serviceId() != null) {
            where.append(" and r.service_id = ?");
            args.add(q.serviceId());
        }
        if (q.resourceId() != null) {
            where.append(" and l.resource_id = ?");
            args.add(q.resourceId());
        }
        if (q.traceId() != null && !q.traceId().isBlank()) {
            String t = q.traceId().trim().toLowerCase(Locale.ROOT);
            if (!TRACE_ID.matcher(t).matches()) {
                throw ApiException.invalidField("traceId", "traceId must be 32 hex characters");
            }
            where.append(" and l.trace_id = ?");
            args.add(t);
        }
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 50 : Math.min(size, PageResponse.MAX_PAGE_SIZE);
        Long total = jdbc.queryForObject("select count(*) from telemetry_logs l join telemetry_resources r on r.id = l.resource_id" + where, Long.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<LogDto> content = jdbc.query("select l.id, l.ts, l.severity, l.severity_text, l.body, l.body_truncated, l.attributes::text, l.trace_id, l.span_id, "
                + "l.resource_id, r.service_name, r.service_id, l.simulated from telemetry_logs l join telemetry_resources r on r.id = l.resource_id" + where
                + " order by l.ts desc, l.id desc limit ? offset ?", (rs, n) -> new LogDto(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getBoolean(6), TelemetryJson.stringMap(rs.getString(7)), rs.getString(8), rs.getString(9), rs.getLong(10),
                rs.getString(11), MappingRuleService.nullableLong(rs, 12), rs.getBoolean(13)), pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(content, p, s, t, (int) ((t + s - 1) / s));
    }

    // ------------------------------------------------------------------ traces

    @Transactional(readOnly = true)
    public List<TraceSummary> traces(TraceQuery q) {
        Instant to = q.to() == null ? clock.instant() : q.to();
        Instant from = q.from() == null ? to.minus(Duration.ofHours(1)) : q.from();
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("from must be before to");
        }
        int limit = q.limit() == null ? 50 : Math.max(1, Math.min(q.limit(), 200));
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where sp.start_time >= ? and sp.start_time < ?");
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        if (!q.includeSimulated()) {
            where.append(" and not sp.simulated");
        }
        if (q.serviceId() != null) {
            where.append(" and sp.trace_id in (select s2.trace_id from telemetry_spans s2 join telemetry_resources r2 on r2.id = s2.resource_id where "
                    + "r2.service_id = ? and s2.start_time >= ? and s2.start_time < ?)");
            args.add(q.serviceId());
            args.add(Timestamp.from(from));
            args.add(Timestamp.from(to));
        }
        if (q.name() != null && !q.name().isBlank()) {
            where.append(" and sp.trace_id in (select s3.trace_id from telemetry_spans s3 where lower(s3.name) like ? escape '\\' and s3.start_time >= ? "
                    + "and s3.start_time < ?)");
            args.add("%" + like(q.name().trim().toLowerCase(Locale.ROOT)) + "%");
            args.add(Timestamp.from(from));
            args.add(Timestamp.from(to));
        }
        StringBuilder having = new StringBuilder(" having true");
        if (q.minDurationMs() != null) {
            having.append(" and extract(epoch from (max(sp.end_time) - min(sp.start_time))) * 1000 >= ?");
            args.add(q.minDurationMs());
        }
        if (Boolean.TRUE.equals(q.error())) {
            having.append(" and sum(case when sp.status_code = 'ERROR' then 1 else 0 end) > 0");
        }
        args.add(limit);
        return jdbc.query("select sp.trace_id, (array_agg(sp.name order by (sp.parent_span_id is null) desc, sp.start_time))[1], "
                + "(array_agg(r.service_name order by (sp.parent_span_id is null) desc, sp.start_time))[1], "
                + "(array_agg(r.service_id order by (sp.parent_span_id is null) desc, sp.start_time))[1], min(sp.start_time), "
                + "extract(epoch from (max(sp.end_time) - min(sp.start_time))) * 1000, count(*), sum(case when sp.status_code = 'ERROR' then 1 else 0 end), "
                + "bool_or(sp.simulated) from telemetry_spans sp join telemetry_resources r on r.id = sp.resource_id" + where + " group by sp.trace_id" + having
                + " order by min(sp.start_time) desc limit ?", (rs, n) -> new TraceSummary(rs.getString(1), rs.getString(2), rs.getString(3),
                MappingRuleService.nullableLong(rs, 4), rs.getTimestamp(5).toInstant(), rs.getDouble(6), rs.getInt(7), rs.getInt(8), rs.getBoolean(9)),
                args.toArray());
    }

    @Transactional(readOnly = true)
    public TraceDetail trace(String traceId) {
        String id = traceId == null ? "" : traceId.trim().toLowerCase(Locale.ROOT);
        if (!TRACE_ID.matcher(id).matches()) {
            throw ApiException.badRequest("traceId must be 32 hex characters");
        }
        record Row(String spanId, String parent, String name, String kind, String serviceName, Long serviceId, long resourceId, Instant start, Instant end,
                   double duration, String status, String statusMessage, Map<String, String> attributes, boolean simulated) {}
        List<Row> rows = jdbc.query("select sp.span_id, sp.parent_span_id, sp.name, sp.kind, r.service_name, r.service_id, sp.resource_id, sp.start_time, "
                + "sp.end_time, sp.duration_ms, sp.status_code, sp.status_message, sp.attributes::text, sp.simulated from telemetry_spans sp join "
                + "telemetry_resources r on r.id = sp.resource_id where sp.trace_id = ? order by sp.start_time, sp.id limit ?", (rs, n) -> new Row(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), MappingRuleService.nullableLong(rs, 6), rs.getLong(7),
                rs.getTimestamp(8).toInstant(), rs.getTimestamp(9).toInstant(), rs.getDouble(10), rs.getString(11), rs.getString(12),
                TelemetryJson.stringMap(rs.getString(13)), rs.getBoolean(14)), id, MAX_TRACE_SPANS + 1);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Trace", id);
        }
        boolean truncated = rows.size() > MAX_TRACE_SPANS;
        List<Row> spans = truncated ? rows.subList(0, MAX_TRACE_SPANS) : rows;
        Instant start = spans.stream().map(Row::start).min(Instant::compareTo).orElseThrow();
        Instant end = spans.stream().map(Row::end).max(Instant::compareTo).orElseThrow();
        Map<String, List<Row>> children = new HashMap<>();
        Set<String> ids = new java.util.HashSet<>();
        spans.forEach(r -> ids.add(r.spanId()));
        List<Row> roots = new ArrayList<>();
        for (Row r : spans) {
            if (r.parent() == null || !ids.contains(r.parent()) || r.parent().equals(r.spanId())) {
                roots.add(r);
            } else {
                children.computeIfAbsent(r.parent(), k -> new ArrayList<>()).add(r);
            }
        }
        java.util.function.Function<Row, SpanNode>[] build = new java.util.function.Function[1];
        Set<String> visited = new java.util.HashSet<>();
        build[0] = r -> {
            visited.add(r.spanId());
            List<SpanNode> kids = children.getOrDefault(r.spanId(), List.of()).stream().filter(c -> !visited.contains(c.spanId()))
                    .sorted(Comparator.comparing(Row::start)).map(build[0]).toList();
            return new SpanNode(r.spanId(), r.parent(), r.name(), r.kind(), r.serviceName(), r.serviceId(), r.resourceId(), r.start(), r.end(), r.duration(),
                    Duration.between(start, r.start()).toNanos() / 1_000_000.0, r.status(), r.statusMessage(), r.attributes(), r.simulated(), kids);
        };
        List<SpanNode> tree = roots.stream().sorted(Comparator.comparing(Row::start)).map(build[0]).toList();
        int errors = (int) spans.stream().filter(r -> "ERROR".equals(r.status())).count();
        return new TraceDetail(id, start, end, Duration.between(start, end).toNanos() / 1_000_000.0, spans.size(), errors,
                spans.stream().anyMatch(Row::simulated), truncated, tree);
    }

    // ------------------------------------------------------------------ RED

    @Transactional(readOnly = true)
    public List<RedSummary> red(Instant from, Instant to, boolean includeSimulated) {
        Instant t = to == null ? clock.instant() : to;
        Instant f = from == null ? t.minus(Duration.ofHours(1)) : from;
        List<Long> serviceIds = jdbc.queryForList("select distinct r.service_id from telemetry_resources r where r.service_id is not null and r.last_seen_at >= ?"
                + (includeSimulated ? "" : " and not r.simulated") + " order by 1 limit 200", Long.class, Timestamp.from(f));
        List<RedSummary> out = new ArrayList<>();
        for (Long id : serviceIds) {
            out.add(red(id, f, t, includeSimulated));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public RedSummary red(Long serviceId, Instant from, Instant to, boolean includeSimulated) {
        Instant t = to == null ? clock.instant() : to;
        Instant f = from == null ? t.minus(Duration.ofHours(1)) : from;
        if (!f.isBefore(t)) {
            throw ApiException.badRequest("from must be before to");
        }
        Map<String, Object> service = jdbc.query("select code, name from business_services where id = ?", rs -> rs.next() ? Map.<String, Object>of("code",
                rs.getString(1), "name", rs.getString(2)) : null, serviceId);
        if (service == null) {
            throw ApiException.notFound("Service", serviceId);
        }
        double minutes = Math.max(1.0, Duration.between(f, t).toSeconds() / 60.0);
        String sim = includeSimulated ? "" : " and not sp.simulated";
        String spanBase = " from telemetry_spans sp join telemetry_resources r on r.id = sp.resource_id where r.service_id = ? and sp.start_time >= ? "
                + "and sp.start_time < ?" + sim;
        Long servers = jdbc.queryForObject("select count(*)" + spanBase + " and sp.kind = 'SERVER'", Long.class, serviceId, Timestamp.from(f), Timestamp.from(t));
        String filter = servers != null && servers > 0 ? " and sp.kind = 'SERVER'" : " and sp.parent_span_id is null";
        Map<String, Object> s = jdbc.queryForMap("select count(*) as n, coalesce(sum(case when sp.status_code = 'ERROR' then 1 else 0 end), 0) as e, "
                + "percentile_cont(0.95) within group (order by sp.duration_ms) as p95, avg(sp.duration_ms) as a, coalesce(bool_or(sp.simulated), false) as sim"
                + spanBase + filter, serviceId, Timestamp.from(f), Timestamp.from(t));
        long requests = ((Number) s.get("n")).longValue();
        String code = (String) service.get("code");
        String name = (String) service.get("name");
        if (requests > 0) {
            long errors = ((Number) s.get("e")).longValue();
            return new RedSummary(serviceId, code, name, f, t, "SPANS", requests, round(requests / minutes), errors, round((double) errors / requests),
                    round(((Number) s.get("p95")).doubleValue()), round(((Number) s.get("a")).doubleValue()), Boolean.TRUE.equals(s.get("sim")), "OK", null);
        }
        // Fallback: request-duration histograms (delta temporality assumed: each point's count is the number of requests it covers).
        String simPoints = includeSimulated ? "" : " and not p.simulated";
        Map<String, Object> m = jdbc.queryForMap("select coalesce(sum(p.count), 0) as n, coalesce(sum(case when coalesce(ms.attributes ->> "
                + "'http.response.status_code', ms.attributes ->> 'http.status_code', '') like '5%' then p.count else 0 end), 0) as e, sum(p.sum) as total, "
                + "max(ms.unit) as unit, coalesce(bool_or(p.simulated), false) as sim from telemetry_metric_points p join telemetry_metric_series ms on "
                + "ms.id = p.series_id join telemetry_resources r on r.id = ms.resource_id where r.service_id = ? and ms.name in ('http.server.request.duration', "
                + "'http.server.duration') and ms.metric_type = 'HISTOGRAM' and p.ts >= ? and p.ts < ?" + simPoints, serviceId, Timestamp.from(f),
                Timestamp.from(t));
        long n = ((Number) m.get("n")).longValue();
        if (n > 0) {
            long errors = ((Number) m.get("e")).longValue();
            double factor = "s".equals(m.get("unit")) ? 1000.0 : 1.0;
            Double avg = m.get("total") == null ? null : round(((Number) m.get("total")).doubleValue() / n * factor);
            return new RedSummary(serviceId, code, name, f, t, "METRICS", n, round(n / minutes), errors, round((double) errors / n), null, avg,
                    Boolean.TRUE.equals(m.get("sim")), "OK", "Duration p95 is not available from aggregated histograms (mean shown)");
        }
        return new RedSummary(serviceId, code, name, f, t, "NONE", 0, null, 0, null, null, null, false, "INSUFFICIENT_DATA",
                "Insufficient data — no spans or request-duration metrics for this service in the window" + (includeSimulated ? "" : " (simulated data excluded)"));
    }

    // ------------------------------------------------------------------ resources

    @Transactional(readOnly = true)
    public PageResponse<ResourceDto> resources(Boolean mapped, String search, Long serviceId, boolean includeSimulated, Integer page, Integer size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where true");
        if (!includeSimulated) {
            where.append(" and not r.simulated");
        }
        if (Boolean.TRUE.equals(mapped)) {
            where.append(" and (r.service_id is not null or r.ci_id is not null)");
        } else if (Boolean.FALSE.equals(mapped)) {
            where.append(" and r.service_id is null and r.ci_id is null");
        }
        if (serviceId != null) {
            where.append(" and r.service_id = ?");
            args.add(serviceId);
        }
        if (search != null && !search.isBlank()) {
            where.append(" and (lower(coalesce(r.service_name, '')) like ? escape '\\' or lower(coalesce(r.host_name, '')) like ? escape '\\')");
            String l = "%" + like(search.trim().toLowerCase(Locale.ROOT)) + "%";
            args.add(l);
            args.add(l);
        }
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, PageResponse.MAX_PAGE_SIZE);
        Long total = jdbc.queryForObject("select count(*) from telemetry_resources r" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<ResourceDto> content = jdbc.query("select r.id, r.service_name, r.service_namespace, r.environment, r.host_name, r.attributes::text, r.service_id, "
                + "b.code, b.name, r.ci_id, c.name, r.mapping_rule_id, r.source_id, r.simulated, r.first_seen_at, r.last_seen_at from telemetry_resources r "
                + "left join business_services b on b.id = r.service_id left join configuration_items c on c.id = r.ci_id" + where
                + " order by r.last_seen_at desc, r.id desc limit ? offset ?", (rs, n) -> new ResourceDto(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), TelemetryJson.stringMap(rs.getString(6)), MappingRuleService.nullableLong(rs, 7), rs.getString(8),
                rs.getString(9), MappingRuleService.nullableLong(rs, 10), rs.getString(11), MappingRuleService.nullableLong(rs, 12),
                MappingRuleService.nullableLong(rs, 13), rs.getBoolean(14), rs.getTimestamp(15).toInstant(), rs.getTimestamp(16).toInstant()),
                pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(content, p, s, t, (int) ((t + s - 1) / s));
    }

    @Transactional(readOnly = true)
    public List<ResourceChangeDto> resourceChanges(Long resourceId) {
        if (jdbc.queryForObject("select count(*) from telemetry_resources where id = ?", Long.class, resourceId) == 0) {
            throw ApiException.notFound("Telemetry resource", resourceId);
        }
        return jdbc.query("select id, resource_id, attribute_key, from_value, to_value, observed_at, simulated from telemetry_resource_changes where "
                + "resource_id = ? order by observed_at desc, id desc limit 500", (rs, n) -> new ResourceChangeDto(rs.getLong(1), rs.getLong(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getBoolean(7)), resourceId);
    }

    // ------------------------------------------------------------------ helpers

    static String like(String v) {
        return v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static Double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        return Math.round(v * 1000.0) / 1000.0;
    }

    static Map<String, String> attributeFilters(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params == null) {
            return out;
        }
        params.forEach((k, v) -> {
            if (k.startsWith("attr.") && v != null) {
                out.put(k.substring(5), v);
            }
        });
        if (out.size() > 10) {
            throw ApiException.badRequest("At most 10 attribute filters");
        }
        return out;
    }
}

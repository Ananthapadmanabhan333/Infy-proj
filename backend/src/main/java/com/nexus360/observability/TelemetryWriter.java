package com.nexus360.observability;

import com.nexus360.observability.TelemetryModel.IngestResult;
import com.nexus360.observability.TelemetryModel.LogRecord;
import com.nexus360.observability.TelemetryModel.MetricPoint;
import com.nexus360.observability.TelemetryModel.Parsed;
import com.nexus360.observability.TelemetryModel.ResourceBatch;
import com.nexus360.observability.TelemetryModel.Span;
import com.nexus360.security.Hashing;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes normalised telemetry (from OTLP, the Prometheus pull adapter or simulation scenarios — always this one path) into the
 * telemetry tables: resources (identity + mapping rules + tracked attribute changes), metric series and raw points (and the minute
 * roll-up queue), log records and spans. {@code simulated} rows are kept apart from real ones (own resources and series).
 */
@Service
@RequiredArgsConstructor
public class TelemetryWriter {

    /** Resource attributes that identify an emitting entity; everything else is descriptive and may change. */
    static final List<String> IDENTITY_KEYS = List.of("service.name", "service.namespace", "deployment.environment", "deployment.environment.name",
            "host.name", "k8s.cluster.name", "k8s.namespace.name", "k8s.deployment.name", "k8s.statefulset.name", "k8s.daemonset.name", "cloud.provider",
            "cloud.account.id", "cloud.region", "faas.name");

    /** Descriptive attributes whose changes are recorded (twin configuration change timeline). */
    static final Set<String> TRACKED_KEYS = Set.of("service.version", "container.image.name", "container.image.tag", "container.image.tags",
            "host.image.id", "host.image.version", "os.version", "process.runtime.version", "telemetry.sdk.version", "faas.version",
            "k8s.deployment.revision");

    /** Where a batch came from. */
    public record Context(Long sourceId, boolean simulated) {}

    private final JdbcTemplate jdbc;
    private final MappingRuleService mappings;
    private final Clock clock;

    @Transactional
    public IngestResult writeMetrics(Context ctx, Parsed<MetricPoint> parsed) {
        Instant now = clock.instant();
        Map<String, Long> resources = new HashMap<>();
        Map<String, Long> series = new HashMap<>();
        Map<Long, Instant[]> seriesBounds = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        Set<Instant> buckets = new TreeSet<>();
        for (ResourceBatch<MetricPoint> batch : parsed.batches()) {
            Instant latest = batch.items().stream().map(MetricPoint::timestamp).max(Instant::compareTo).orElse(now);
            long resourceId = resource(ctx, batch.resource(), latest, resources);
            for (MetricPoint p : batch.items()) {
                String key = Hashing.sha256Hex(resourceId + "\n" + p.name() + "\n" + p.type() + "\n" + TelemetryJson.canonical(p.attributes()));
                Long seriesId = series.get(key);
                if (seriesId == null) {
                    seriesId = jdbc.queryForObject("insert into telemetry_metric_series (series_key, resource_id, name, metric_type, unit, attributes, simulated, "
                            + "first_seen_at, last_seen_at) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) on conflict (series_key) do update set "
                            + "first_seen_at = least(telemetry_metric_series.first_seen_at, excluded.first_seen_at), "
                            + "last_seen_at = greatest(telemetry_metric_series.last_seen_at, excluded.last_seen_at), "
                            + "unit = coalesce(excluded.unit, telemetry_metric_series.unit) "
                            + "returning id", Long.class, key, resourceId, p.name(), p.type().name(), p.unit(), TelemetryJson.write(p.attributes()), ctx.simulated(),
                            Timestamp.from(p.timestamp()), Timestamp.from(p.timestamp()));
                    series.put(key, seriesId);
                }
                Instant[] b = seriesBounds.computeIfAbsent(seriesId, k -> new Instant[] {p.timestamp(), p.timestamp()});
                if (p.timestamp().isBefore(b[0])) b[0] = p.timestamp();
                if (p.timestamp().isAfter(b[1])) b[1] = p.timestamp();
                rows.add(new Object[] {seriesId, Timestamp.from(p.timestamp()), p.value(), p.count(), p.sum(), p.min(), p.max(), ctx.simulated()});
                buckets.add(p.timestamp().truncatedTo(ChronoUnit.MINUTES));
            }
        }
        for (var e : seriesBounds.entrySet()) {
            jdbc.update("update telemetry_metric_series set first_seen_at = least(first_seen_at, ?), last_seen_at = greatest(last_seen_at, ?) where id = ?",
                    Timestamp.from(e.getValue()[0]), Timestamp.from(e.getValue()[1]), e.getKey());
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("insert into telemetry_metric_points (series_id, ts, value, count, sum, min_value, max_value, simulated) values (?, ?, ?, ?, ?, ?, ?, ?)", rows);
            List<Object[]> queue = buckets.stream().map(b -> new Object[] {Timestamp.from(b)}).toList();
            // DO UPDATE (not DO NOTHING) waits for a roll-up that is processing the minute, so points committed after it are re-queued.
            jdbc.batchUpdate("insert into telemetry_rollup_queue (bucket_start) values (?) on conflict (bucket_start) do update set queued_at = now()", queue);
        }
        return new IngestResult(rows.size(), parsed.rejected(), parsed.errorMessage());
    }

    @Transactional
    public IngestResult writeLogs(Context ctx, Parsed<LogRecord> parsed) {
        Map<String, Long> resources = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (ResourceBatch<LogRecord> batch : parsed.batches()) {
            Instant latest = batch.items().stream().map(LogRecord::timestamp).max(Instant::compareTo).orElse(clock.instant());
            long resourceId = resource(ctx, batch.resource(), latest, resources);
            for (LogRecord r : batch.items()) {
                rows.add(new Object[] {resourceId, Timestamp.from(r.timestamp()), r.observedAt() == null ? null : Timestamp.from(r.observedAt()), r.severity(),
                        r.severityNumber(), r.severityText(), r.body(), r.bodyTruncated(), TelemetryJson.write(r.attributes()), r.droppedAttributes(),
                        r.traceId(), r.spanId(), ctx.simulated()});
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("insert into telemetry_logs (resource_id, ts, observed_at, severity, severity_number, severity_text, body, body_truncated, attributes, "
                    + "dropped_attributes, trace_id, span_id, simulated) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)", rows);
        }
        return new IngestResult(rows.size(), parsed.rejected(), parsed.errorMessage());
    }

    @Transactional
    public IngestResult writeSpans(Context ctx, Parsed<Span> parsed) {
        Map<String, Long> resources = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (ResourceBatch<Span> batch : parsed.batches()) {
            Instant latest = batch.items().stream().map(Span::end).max(Instant::compareTo).orElse(clock.instant());
            long resourceId = resource(ctx, batch.resource(), latest, resources);
            for (Span s : batch.items()) {
                double durationMs = Duration.between(s.start(), s.end()).toNanos() / 1_000_000.0;
                rows.add(new Object[] {resourceId, s.traceId(), s.spanId(), s.parentSpanId(), s.name(), s.kind(), Timestamp.from(s.start()),
                        Timestamp.from(s.end()), durationMs, s.statusCode(), s.statusMessage(), TelemetryJson.write(s.attributes()), s.droppedAttributes(),
                        ctx.simulated()});
            }
        }
        if (!rows.isEmpty()) {
            // OTLP exporters retry failed exports: a span that is already stored is ignored (still accepted).
            jdbc.batchUpdate("insert into telemetry_spans (resource_id, trace_id, span_id, parent_span_id, name, kind, start_time, end_time, duration_ms, "
                    + "status_code, status_message, attributes, dropped_attributes, simulated) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) "
                    + "on conflict (trace_id, span_id) do nothing", rows);
        }
        return new IngestResult(rows.size(), parsed.rejected(), parsed.errorMessage());
    }

    // ------------------------------------------------------------------ resources

    /** Identity key of a resource: simulated flag + the identity attributes present (sorted). */
    static String resourceKey(Map<String, String> attributes, boolean simulated) {
        Map<String, String> identity = new TreeMap<>();
        for (String k : IDENTITY_KEYS) {
            String v = attributes.get(k);
            if (v != null && !v.isBlank()) {
                identity.put(k, v);
            }
        }
        return Hashing.sha256Hex((simulated ? "simulated" : "real") + "\n" + TelemetryJson.write(identity));
    }

    private record ResourceRow(long id, Map<String, String> attributes, Long serviceId, Long ciId, Long ruleId, Instant lastSeen) {}

    /** Finds or creates the resource, applies the mapping rules and records tracked attribute changes; cached per request. */
    long resource(Context ctx, Map<String, String> attributes, Instant seenAt, Map<String, Long> cache) {
        String key = resourceKey(attributes, ctx.simulated());
        Long cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Instant now = clock.instant();
        MappingRuleService.Match match = mappings.resolve(attributes);
        ResourceRow row = find(key, false);
        if (row == null) {
            Long id = jdbc.query("insert into telemetry_resources (resource_key, service_name, service_namespace, environment, host_name, attributes, service_id, "
                    + "ci_id, mapping_rule_id, source_id, simulated, first_seen_at, last_seen_at) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?) "
                    + "on conflict (resource_key) do nothing returning id", rs -> rs.next() ? rs.getLong(1) : null, key,
                    trim(attributes.get("service.name"), 200), trim(attributes.get("service.namespace"), 200),
                    trim(attributes.getOrDefault("deployment.environment", attributes.get("deployment.environment.name")), 100),
                    trim(attributes.get("host.name"), 255), TelemetryJson.write(attributes), match.serviceId(), match.ciId(), match.ruleId(), ctx.sourceId(),
                    ctx.simulated(), Timestamp.from(seenAt), Timestamp.from(seenAt));
            if (id != null) {
                mappings.recordMatch(match.ruleId(), now);
                cache.put(key, id);
                return id;
            }
            row = find(key, false);
            if (row == null) {
                throw new IllegalStateException("Telemetry resource could not be stored");
            }
        }
        boolean attributesChanged = !Objects.equals(new TreeMap<>(row.attributes()), new TreeMap<>(attributes));
        boolean mappingChanged = !Objects.equals(match.serviceId(), row.serviceId()) || !Objects.equals(match.ciId(), row.ciId())
                || !Objects.equals(match.ruleId(), row.ruleId());
        boolean stale = row.lastSeen() == null || row.lastSeen().isBefore(seenAt.minusSeconds(60));
        if (attributesChanged || mappingChanged || stale) {
            ResourceRow locked = find(key, true);
            if (attributesChanged) {
                for (String k : TRACKED_KEYS) {
                    String before = locked.attributes().get(k);
                    String after = attributes.get(k);
                    if (before != null && after != null && !before.equals(after)) {
                        jdbc.update("insert into telemetry_resource_changes (resource_id, attribute_key, from_value, to_value, observed_at, simulated) "
                                + "values (?, ?, ?, ?, ?, ?)", locked.id(), k, trim(before, 500), trim(after, 500), Timestamp.from(seenAt), ctx.simulated());
                    }
                }
            }
            // Attributes not sent this time are kept (exporters may send a subset); sent ones win.
            Map<String, String> merged = new LinkedHashMap<>(locked.attributes());
            merged.putAll(attributes);
            jdbc.update("update telemetry_resources set attributes = ?::jsonb, service_id = ?, ci_id = ?, mapping_rule_id = ?, last_seen_at = greatest(last_seen_at, ?), "
                    + "updated_at = ? where id = ?", TelemetryJson.write(merged), match.serviceId(), match.ciId(), match.ruleId(), Timestamp.from(seenAt),
                    Timestamp.from(now), locked.id());
            if (mappingChanged) {
                mappings.recordMatch(match.ruleId(), now);
            }
        }
        cache.put(key, row.id());
        return row.id();
    }

    private ResourceRow find(String key, boolean lock) {
        return jdbc.query("select id, attributes::text, service_id, ci_id, mapping_rule_id, last_seen_at from telemetry_resources where resource_key = ?"
                + (lock ? " for update" : ""), rs -> rs.next() ? new ResourceRow(rs.getLong(1), TelemetryJson.stringMap(rs.getString(2)),
                MappingRuleService.nullableLong(rs, 3), MappingRuleService.nullableLong(rs, 4), MappingRuleService.nullableLong(rs, 5),
                MappingRuleService.instant(rs, 6)) : null, key);
    }

    private static String trim(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}

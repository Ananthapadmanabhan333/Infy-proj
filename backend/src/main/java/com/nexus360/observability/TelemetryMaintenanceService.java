package com.nexus360.observability;

import com.nexus360.admin.SettingsService;
import com.nexus360.audit.AuditService;
import com.nexus360.observability.ObservabilityDtos.PurgeResult;
import com.nexus360.observability.ObservabilityDtos.RetentionDto;
import com.nexus360.observability.ObservabilityDtos.RetentionRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minute roll-ups and retention. Every write of raw metric points queues the minutes it touched ({@code telemetry_rollup_queue});
 * {@link #processPending} recomputes those minutes from the raw points (count, sum, min, max, avg and the 95th percentile of the
 * points in the minute) — late and back-filled data is therefore rolled up too. {@link #purge} deletes raw points, roll-ups, logs and
 * spans older than the retention settings and then series and resources that no longer have data.
 */
@Service
@RequiredArgsConstructor
public class TelemetryMaintenanceService {

    static final String RAW_DAYS = "observability.retention.raw_days";
    static final String ROLLUP_DAYS = "observability.retention.rollup_days";
    static final String LOGS_DAYS = "observability.retention.logs_days";
    static final String TRACES_DAYS = "observability.retention.traces_days";
    private static final int DELETE_BATCH = 50_000;

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final AuditService audit;
    private final Clock clock;

    /** Rolls up at most {@code maxBuckets} queued minutes (oldest first); returns the number of minutes processed. */
    @Transactional
    public int processPending(int maxBuckets) {
        return process(jdbc.queryForList("select bucket_start from telemetry_rollup_queue order by bucket_start limit ? for update skip locked",
                Timestamp.class, maxBuckets));
    }

    /** Rolls up the queued minutes inside [{@code from}, {@code to}) (used by queries so that the latest minutes are current). */
    @Transactional
    public int processPending(Instant from, Instant to) {
        return process(jdbc.queryForList("select bucket_start from telemetry_rollup_queue where bucket_start >= ? and bucket_start < ? order by bucket_start "
                + "limit 10000 for update skip locked", Timestamp.class, Timestamp.from(from.minusSeconds(60)), Timestamp.from(to)));
    }

    private int process(List<Timestamp> buckets) {
        for (Timestamp bucket : buckets) {
            Timestamp end = Timestamp.from(bucket.toInstant().plusSeconds(60));
            jdbc.update("insert into telemetry_metric_rollups (series_id, bucket_start, sample_count, sum_value, min_value, max_value, avg_value, p95_value, "
                    + "simulated, updated_at) select series_id, ?, count(*), sum(value), min(value), max(value), avg(value), "
                    + "percentile_cont(0.95) within group (order by value), bool_or(simulated), now() from telemetry_metric_points where ts >= ? and ts < ? "
                    + "group by series_id on conflict (series_id, bucket_start) do update set sample_count = excluded.sample_count, sum_value = excluded.sum_value, "
                    + "min_value = excluded.min_value, max_value = excluded.max_value, avg_value = excluded.avg_value, p95_value = excluded.p95_value, "
                    + "simulated = excluded.simulated, updated_at = now()", bucket, bucket, end);
            jdbc.update("delete from telemetry_rollup_queue where bucket_start = ?", bucket);
        }
        return buckets.size();
    }

    // ------------------------------------------------------------------ retention

    public RetentionDto retention() {
        return new RetentionDto(settings.getInt(RAW_DAYS, 7), settings.getInt(ROLLUP_DAYS, 90), settings.getInt(LOGS_DAYS, 14),
                settings.getInt(TRACES_DAYS, 7));
    }

    /** Updates the four retention settings (each audited by the settings service). Roll-ups must outlive raw points. */
    @Transactional
    public RetentionDto updateRetention(RetentionRequest r) {
        if (r.rollupDays() < r.rawDays()) {
            throw com.nexus360.common.ApiException.invalidField("rollupDays", "Roll-ups must be kept at least as long as raw points");
        }
        settings.update(RAW_DAYS, String.valueOf(r.rawDays()));
        settings.update(ROLLUP_DAYS, String.valueOf(r.rollupDays()));
        settings.update(LOGS_DAYS, String.valueOf(r.logsDays()));
        settings.update(TRACES_DAYS, String.valueOf(r.tracesDays()));
        return retention();
    }

    /** Deletes telemetry older than the retention settings (in batches); audited as {@code TELEMETRY_RETENTION_PURGED}. */
    public PurgeResult purge() {
        Instant now = clock.instant();
        RetentionDto r = retention();
        Timestamp rawCutoff = Timestamp.from(now.minus(Duration.ofDays(Math.max(1, r.rawDays()))));
        Timestamp rollupCutoff = Timestamp.from(now.minus(Duration.ofDays(Math.max(1, r.rollupDays()))));
        Timestamp logsCutoff = Timestamp.from(now.minus(Duration.ofDays(Math.max(1, r.logsDays()))));
        Timestamp tracesCutoff = Timestamp.from(now.minus(Duration.ofDays(Math.max(1, r.tracesDays()))));
        long points = batched("delete from telemetry_metric_points where id in (select id from telemetry_metric_points where ts < ? limit " + DELETE_BATCH + ")",
                rawCutoff);
        long rollups = batched("delete from telemetry_metric_rollups where ctid in (select ctid from telemetry_metric_rollups where bucket_start < ? limit "
                + DELETE_BATCH + ")", rollupCutoff);
        long logs = batched("delete from telemetry_logs where id in (select id from telemetry_logs where ts < ? limit " + DELETE_BATCH + ")", logsCutoff);
        long spans = batched("delete from telemetry_spans where id in (select id from telemetry_spans where start_time < ? limit " + DELETE_BATCH + ")",
                tracesCutoff);
        jdbc.update("delete from telemetry_rollup_queue where bucket_start < ?", rawCutoff);
        long series = jdbc.update("delete from telemetry_metric_series s where s.last_seen_at < ? and not exists (select 1 from telemetry_metric_points p where "
                + "p.series_id = s.id) and not exists (select 1 from telemetry_metric_rollups u where u.series_id = s.id) and not exists (select 1 from "
                + "telemetry_anomalies a where a.series_id = s.id and a.status = 'OPEN')", rollupCutoff);
        Timestamp oldest = rollupCutoff.before(logsCutoff) ? rollupCutoff : logsCutoff;
        long resources = jdbc.update("delete from telemetry_resources r where r.last_seen_at < ? and not exists (select 1 from telemetry_metric_series s where "
                + "s.resource_id = r.id) and not exists (select 1 from telemetry_logs l where l.resource_id = r.id) and not exists (select 1 from "
                + "telemetry_spans p where p.resource_id = r.id)", oldest.before(tracesCutoff) ? oldest : tracesCutoff);
        PurgeResult result = new PurgeResult(now, points, rollups, logs, spans, series, resources);
        if (points + rollups + logs + spans + series + resources > 0) {
            audit.record("TELEMETRY_RETENTION_PURGED", "TELEMETRY", null, Map.of("points", points, "rollups", rollups, "logs", logs, "spans", spans,
                    "series", series, "resources", resources));
        }
        return result;
    }

    private long batched(String sql, Timestamp cutoff) {
        long total = 0;
        int n;
        do {
            n = jdbc.update(sql, cutoff);
            total += n;
        } while (n >= DELETE_BATCH);
        return total;
    }
}

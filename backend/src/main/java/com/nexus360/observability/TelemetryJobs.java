package com.nexus360.observability;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SA Observability background jobs; each runs on one instance at a time under a PostgreSQL transaction-scoped advisory lock:
 * <ul>
 *   <li>360011 — minute roll-ups of queued minutes ({@code app.observability.rollup-interval-ms}, 60 s)</li>
 *   <li>360012 — retention purge ({@code app.observability.retention-cron}, daily 03:50)</li>
 *   <li>360013 — anomaly detection over every enabled detector ({@code app.observability.anomaly-interval-ms}, 60 s)</li>
 *   <li>360014 — Prometheus pulls that are due ({@code app.observability.pull-poll-interval-ms}, 30 s)</li>
 * </ul>
 * All of them are switched off with {@code app.observability.jobs-enabled=false} (OBSERVABILITY_JOBS_ENABLED); tests drive them
 * explicitly. (360015 serialises correlation decisions, 360010 the simulation scheduler.)
 */
@Slf4j
@Component
public class TelemetryJobs {

    public static final long ROLLUP_LOCK = 360_011L;
    public static final long RETENTION_LOCK = 360_012L;
    public static final long ANOMALY_LOCK = 360_013L;
    public static final long PULL_LOCK = 360_014L;

    private final TelemetryMaintenanceService maintenance;
    private final AnomalyService anomalies;
    private final TelemetrySourceService sources;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    private final AtomicReference<Instant> lastRollup = new AtomicReference<>();

    public TelemetryJobs(TelemetryMaintenanceService maintenance, AnomalyService anomalies, TelemetrySourceService sources, JdbcTemplate jdbc,
                         PlatformTransactionManager transactions, Clock clock, @Value("${app.observability.jobs-enabled:true}") boolean enabled) {
        this.maintenance = maintenance;
        this.anomalies = anomalies;
        this.sources = sources;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public Instant lastRollupAt() {
        return lastRollup.get();
    }

    @Scheduled(fixedDelayString = "${app.observability.rollup-interval-ms:60000}", initialDelay = 40_000)
    public void rollups() {
        if (enabled) {
            locked(ROLLUP_LOCK, "roll-up", () -> {
                int n;
                int total = 0;
                do {
                    n = maintenance.processPending(500);
                    total += n;
                } while (n == 500 && total < 20_000);
                lastRollup.set(clock.instant());
            });
        }
    }

    @Scheduled(cron = "${app.observability.retention-cron:0 50 3 * * *}")
    public void retention() {
        if (enabled) {
            locked(RETENTION_LOCK, "retention purge", maintenance::purge);
        }
    }

    @Scheduled(fixedDelayString = "${app.observability.anomaly-interval-ms:60000}", initialDelay = 55_000)
    public void anomalyDetection() {
        if (enabled) {
            locked(ANOMALY_LOCK, "anomaly detection", anomalies::runAll);
        }
    }

    @Scheduled(fixedDelayString = "${app.observability.pull-poll-interval-ms:30000}", initialDelay = 45_000)
    public void pulls() {
        if (enabled) {
            locked(PULL_LOCK, "Prometheus pull", () -> {
                for (Long id : sources.duePulls()) {
                    try {
                        sources.pull(id, null);
                    } catch (RuntimeException e) {
                        log.warn("Pull of telemetry source {} failed: {}", id, e.getMessage());
                    }
                }
            });
        }
    }

    private void locked(long key, String name, Runnable work) {
        try {
            tx.executeWithoutResult(status -> {
                Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, key);
                if (Boolean.TRUE.equals(locked)) {
                    work.run();
                }
            });
        } catch (RuntimeException e) {
            log.error("Observability {} job failed", name, e);
        }
    }
}

package com.nexus360.sla;

import com.nexus360.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Periodic SLA evaluation. A transaction-scoped PostgreSQL advisory lock guarantees that only one API
 * instance evaluates at a time when the service is scaled horizontally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaScheduler {

    private static final long LOCK_KEY = 360_001L;

    private final SlaEngine engine;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AppProperties properties;
    private volatile java.time.Instant lastRunAt;

    /** When this instance last completed an evaluation (null before the first run or when the scheduler is disabled). */
    public java.time.Instant lastRunAt() {
        return lastRunAt;
    }

    @Scheduled(fixedDelayString = "${app.sla.evaluation-interval-ms:60000}", initialDelay = 20_000)
    public void run() {
        if (!properties.sla().schedulerEnabled()) {
            return;
        }
        try {
            SlaEngine.EvaluationResult result = transactions.execute(status -> {
                Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
                return Boolean.TRUE.equals(locked) ? engine.evaluate() : null;
            });
            lastRunAt = java.time.Instant.now();
            if (result != null && (result.atRisk() + result.breached() + result.escalated()) > 0) {
                log.info("SLA evaluation: {} running, {} newly at risk, {} breached, {} escalated",
                        result.evaluated(), result.atRisk(), result.breached(), result.escalated());
            }
        } catch (RuntimeException ex) {
            log.error("SLA evaluation failed", ex);
        }
    }
}

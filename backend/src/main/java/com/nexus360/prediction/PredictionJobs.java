package com.nexus360.prediction;

import com.nexus360.prediction.PredictionDtos.EvaluateRequest;
import com.nexus360.prediction.PredictionDtos.ModelType;
import com.nexus360.prediction.PredictionDtos.TrainRequest;
import com.nexus360.security.Actor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled predictive-intelligence jobs, each on one instance at a time (transaction-scoped advisory lock held while it runs):
 * <ul>
 *   <li>360016 — weekly training ({@code app.predictions.training-cron}, Sundays 04:20): a new TRAINED (or INSUFFICIENT_DATA) version of
 *       SLA_BREACH, INCIDENT_RECURRENCE, CAPACITY_RISK and SERVICE_HEALTH on the default window, and a re-evaluation of every ACTIVE
 *       ANOMALY registration. Nothing is activated automatically.</li>
 *   <li>360017 — outcome reconciliation and drift checks ({@code app.predictions.outcome-interval-ms}, 15 min).</li>
 * </ul>
 * Both are off with {@code app.predictions.jobs-enabled=false} (PREDICTION_JOBS_ENABLED); tests drive them explicitly.
 */
@Slf4j
@Component
public class PredictionJobs {

    public static final long TRAINING_LOCK = 360_016L;
    public static final long OUTCOME_LOCK = 360_017L;

    private final PredictionModelService models;
    private final PredictionOutcomeService outcomes;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public PredictionJobs(PredictionModelService models, PredictionOutcomeService outcomes, JdbcTemplate jdbc, PlatformTransactionManager transactions,
                          @Value("${app.predictions.jobs-enabled:true}") boolean enabled) {
        this.models = models;
        this.outcomes = outcomes;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.predictions.training-cron:0 20 4 * * SUN}")
    public void weeklyTraining() {
        if (enabled) {
            locked(TRAINING_LOCK, "weekly training", this::trainAll);
        }
    }

    @Scheduled(fixedDelayString = "${app.predictions.outcome-interval-ms:900000}", initialDelay = 120_000)
    public void outcomeReconciliation() {
        if (enabled) {
            locked(OUTCOME_LOCK, "outcome reconciliation", outcomes::reconcile);
        }
    }

    /** One scheduled training round (also used by tests). */
    public void trainAll() {
        for (ModelType type : new ModelType[]{ModelType.SLA_BREACH, ModelType.INCIDENT_RECURRENCE, ModelType.CAPACITY_RISK, ModelType.SERVICE_HEALTH}) {
            try {
                models.train(new TrainRequest(type, null, null, false, null, null, null, null, null, null), Actor.SYSTEM, "SCHEDULE");
            } catch (RuntimeException e) {
                log.warn("Scheduled training of {} failed: {}", type, e.getMessage());
            }
        }
        for (PredictionModelService.Model m : models.activeModels()) {
            if (m.type() == ModelType.ANOMALY) {
                try {
                    models.evaluate(m.id(), new EvaluateRequest(null, null, null, null, null), Actor.SYSTEM);
                } catch (RuntimeException e) {
                    log.warn("Scheduled evaluation of ANOMALY registration {} failed: {}", m.id(), e.getMessage());
                }
            }
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
            log.error("Prediction {} job failed", name, e);
        }
    }
}

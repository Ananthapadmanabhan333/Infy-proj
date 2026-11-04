package com.nexus360.approval;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Escalates approval stages past their deadline. One instance at a time (PostgreSQL advisory lock 360007); every stage is
 * escalated exactly once ({@code escalated_at} claimed with a conditional update), so overlapping runs are harmless.
 */
@Slf4j
@Component
public class ApprovalDeadlineScheduler {

    static final long LOCK_KEY = 360_007L;

    private final ApprovalService approvals;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate perStage;
    private final Clock clock;
    private final boolean enabled;

    public ApprovalDeadlineScheduler(ApprovalService approvals, JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock,
                                     @Value("${app.approvals.scheduler-enabled:true}") boolean enabled) {
        this.approvals = approvals;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.perStage = new TransactionTemplate(transactions);
        this.perStage.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.approvals.escalation-poll-interval-ms:60000}", initialDelay = 45_000)
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            int n = runEscalations();
            if (n > 0) {
                log.info("Escalated {} overdue approval stage(s)", n);
            }
        } catch (RuntimeException ex) {
            log.error("Approval escalation failed", ex);
        }
    }

    /** Escalates every overdue stage now; returns how many were escalated by this run. */
    public int runEscalations() {
        Integer result = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            Instant now = clock.instant();
            List<Long> due = approvals.overdueStageIds(now, 100);
            int escalated = 0;
            for (Long id : due) {
                try {
                    Boolean done = perStage.execute(s -> approvals.escalateStage(id, now));
                    escalated += Boolean.TRUE.equals(done) ? 1 : 0;
                } catch (RuntimeException ex) {
                    log.error("Could not escalate approval stage {}", id, ex);
                }
            }
            return escalated;
        });
        return result == null ? 0 : result;
    }
}

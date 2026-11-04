package com.nexus360.alert;

import com.nexus360.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Escalation of unacknowledged alerts through the {@link AlertEscalationPolicy} beans, and the end of time-limited manual
 * suppressions. The scheduler runs on one instance at a time (PostgreSQL advisory lock 360008); every alert is escalated in its
 * own transaction with optimistic locking, so each level fires exactly once.
 */
@Slf4j
@Component
public class AlertEscalationService {

    static final long LOCK_KEY = 360_008L;

    private final List<AlertEscalationPolicy> policies;
    private final AlertRepository alerts;
    private final AlertHistory history;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate perAlert;
    private final Clock clock;
    private final boolean enabled;

    public AlertEscalationService(List<AlertEscalationPolicy> policies, AlertRepository alerts, AlertHistory history, JdbcTemplate jdbc,
                                  PlatformTransactionManager transactions, Clock clock,
                                  @Value("${app.alerts.escalation-enabled:true}") boolean enabled) {
        List<AlertEscalationPolicy> sorted = new ArrayList<>(policies);
        sorted.sort(Comparator.comparingInt(AlertEscalationPolicy::order).thenComparing(AlertEscalationPolicy::name));
        this.policies = List.copyOf(sorted);
        this.alerts = alerts;
        this.history = history;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.perAlert = new TransactionTemplate(transactions);
        this.perAlert.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.enabled = enabled;
    }

    /** The policy that handles the alert (lowest order that applies), or {@code null}. */
    public AlertEscalationPolicy policyFor(Alert alert) {
        for (AlertEscalationPolicy p : policies) {
            if (p.appliesTo(alert)) {
                return p;
            }
        }
        return null;
    }

    public List<AlertEscalationPolicy> policies() {
        return policies;
    }

    /** Plans the next escalation of an OPEN alert ({@code reference} = when it became active); clears it for other states. */
    public void schedule(Alert alert, Instant reference) {
        if (alert.getStatus() != Alert.Status.OPEN) {
            alert.setNextEscalationAt(null);
            return;
        }
        AlertEscalationPolicy policy = policyFor(alert);
        alert.setNextEscalationAt(policy == null ? null : policy.nextEscalationAt(alert, reference));
    }

    @Scheduled(fixedDelayString = "${app.alerts.escalation-poll-interval-ms:60000}", initialDelay = 50_000)
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            int n = runEscalations();
            if (n > 0) {
                log.info("Escalated {} unacknowledged alert(s)", n);
            }
        } catch (RuntimeException ex) {
            log.error("Alert escalation failed", ex);
        }
    }

    /** Escalates every due alert and ends expired manual suppressions now; returns the number of escalations. */
    public int runEscalations() {
        Integer result = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            Instant now = clock.instant();
            for (Long id : jdbc.queryForList("select id from alerts where status = 'SUPPRESSED' and suppressed_until is not null and suppressed_until <= ? "
                    + "order by suppressed_until limit 200", Long.class, java.sql.Timestamp.from(now))) {
                try {
                    perAlert.executeWithoutResult(s -> endSuppression(id, now));
                } catch (RuntimeException ex) {
                    log.error("Could not end the suppression of alert {}", id, ex);
                }
            }
            int escalated = 0;
            for (Long id : jdbc.queryForList("select id from alerts where status = 'OPEN' and next_escalation_at <= ? order by next_escalation_at limit 100",
                    Long.class, java.sql.Timestamp.from(now))) {
                try {
                    Boolean done = perAlert.execute(s -> escalateOne(id, now));
                    escalated += Boolean.TRUE.equals(done) ? 1 : 0;
                } catch (RuntimeException ex) {
                    log.error("Could not escalate alert {}", id, ex);
                }
            }
            return escalated;
        });
        return result == null ? 0 : result;
    }

    private boolean escalateOne(Long id, Instant now) {
        Alert a = alerts.findWithDetailsById(id).orElse(null);
        if (a == null || a.getStatus() != Alert.Status.OPEN || a.getNextEscalationAt() == null || a.getNextEscalationAt().isAfter(now)) {
            return false;
        }
        AlertEscalationPolicy policy = policyFor(a);
        if (policy == null) {
            a.setNextEscalationAt(null);
            return false;
        }
        int level = a.getEscalationLevel() + 1;
        a.setEscalationLevel(level);
        a.setLastEscalatedAt(now);
        AlertEscalationPolicy.Outcome outcome = policy.escalate(a, level, now);
        a.setNextEscalationAt(policy.nextEscalationAt(a, now));
        alerts.saveAndFlush(a);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("level", level);
        details.put("policy", policy.name());
        details.put("notified", outcome == null || outcome.notifiedUserIds() == null ? 0 : outcome.notifiedUserIds().size());
        history.record(id, "ESCALATED", Actor.SYSTEM, outcome == null ? "Escalated to level " + level : outcome.note(), details);
        return true;
    }

    private void endSuppression(Long id, Instant now) {
        Alert a = alerts.findWithDetailsById(id).orElse(null);
        if (a == null || a.getStatus() != Alert.Status.SUPPRESSED || a.getSuppressedUntil() == null || a.getSuppressedUntil().isAfter(now)) {
            return;
        }
        a.setSuppressedUntil(null);
        a.setSuppressedAt(null);
        a.setSuppressedBy(null);
        a.setSuppressionRule(null);
        if (a.isFiring()) {
            a.setStatus(Alert.Status.OPEN);
            schedule(a, now);
            history.record(id, "UNSUPPRESSED", Actor.SYSTEM, "The suppression period ended; the alert is still firing", null);
        } else {
            a.setStatus(Alert.Status.RESOLVED);
            a.setResolvedAt(now);
            history.record(id, "AUTO_RESOLVED", Actor.SYSTEM, "The suppression period ended; the source reports the alert as resolved", null);
        }
        alerts.saveAndFlush(a);
    }
}

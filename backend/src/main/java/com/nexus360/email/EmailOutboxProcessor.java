package com.nexus360.email;

import com.nexus360.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the e-mail outbox: each due PENDING message is locked ({@code FOR UPDATE SKIP LOCKED}, so several API instances
 * never send the same message twice), sent over SMTP and marked SENT; failures are retried with exponential backoff up to
 * {@code MAIL_MAX_ATTEMPTS}, then marked FAILED. Without SMTP configuration pending messages become SKIPPED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailOutboxProcessor {

    private static final int BATCH = 50;

    private final EmailOutboxRepository outbox;
    private final MailTransport transport;
    private final TransactionTemplate transactions;
    private final AppProperties properties;
    private final Clock clock;
    private volatile Instant lastRunAt;

    public record DrainResult(int sent, int retried, int failed, int skipped) {}

    public Instant lastRunAt() {
        return lastRunAt;
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-interval-ms:30000}", initialDelay = 15_000)
    public void poll() {
        if (properties.mail() == null || !properties.mail().outboxEnabled()) {
            return;
        }
        try {
            DrainResult r = drain();
            if (r.sent() + r.retried() + r.failed() + r.skipped() > 0) {
                log.info("E-mail outbox: {} sent, {} retried, {} failed, {} skipped", r.sent(), r.retried(), r.failed(), r.skipped());
            }
        } catch (RuntimeException ex) {
            log.error("E-mail outbox processing failed", ex);
        }
    }

    /** Finished messages (sent, failed, skipped) are kept for 30 days for troubleshooting. */
    @Scheduled(cron = "0 40 3 * * *")
    public void purge() {
        Integer removed = transactions.execute(status -> outbox.deleteFinishedBefore(clock.instant().minus(Duration.ofDays(30))));
        if (removed != null && removed > 0) {
            log.info("Purged {} finished e-mail outbox rows", removed);
        }
    }

    /** Processes up to one batch of due messages (also called directly by tests and administrators' "send now"). */
    public DrainResult drain() {
        int sent = 0;
        int retried = 0;
        int failed = 0;
        int skipped = 0;
        if (!transport.configured()) {
            Integer n = transactions.execute(status -> {
                Instant now = clock.instant();
                var pending = outbox.lockAllPending();
                pending.forEach(m -> {
                    m.setLastError("SMTP is not configured (MAIL_HOST)");
                    m.finish(EmailMessage.Status.SKIPPED, now);
                });
                return pending.size();
            });
            lastRunAt = clock.instant();
            return new DrainResult(0, 0, 0, n == null ? 0 : n);
        }
        for (int i = 0; i < BATCH; i++) {
            Outcome outcome = transactions.execute(status -> processNext());
            if (outcome == null || outcome == Outcome.NONE) {
                break;
            }
            switch (outcome) {
                case SENT -> sent++;
                case RETRY -> retried++;
                case FAILED -> failed++;
                default -> { }
            }
        }
        lastRunAt = clock.instant();
        return new DrainResult(sent, retried, failed, skipped);
    }

    private enum Outcome { NONE, SENT, RETRY, FAILED }

    private Outcome processNext() {
        Instant now = clock.instant();
        Optional<EmailMessage> next = outbox.lockNextDue(now);
        if (next.isEmpty()) {
            return Outcome.NONE;
        }
        EmailMessage m = next.get();
        m.setAttempts(m.getAttempts() + 1);
        try {
            transport.send(m);
            m.setLastError(null);
            m.finish(EmailMessage.Status.SENT, clock.instant());
            return Outcome.SENT;
        } catch (RuntimeException e) {
            String error = describe(e);
            m.setLastError(error.length() > 1000 ? error.substring(0, 999) + "…" : error);
            int max = properties.mail().maxAttempts();
            if (m.getAttempts() >= max) {
                m.finish(EmailMessage.Status.FAILED, now);
                log.warn("E-mail {} to {} failed permanently after {} attempts: {}", m.getId(), mask(m.getToAddress()), m.getAttempts(), error);
                return Outcome.FAILED;
            }
            Duration delay = EmailService.backoff(m.getAttempts());
            m.setNextAttemptAt(now.plus(delay));
            log.info("E-mail {} attempt {} failed, retrying in {} s: {}", m.getId(), m.getAttempts(), delay.toSeconds(), error);
            return Outcome.RETRY;
        }
    }

    /** Error text without credentials (exception messages of the mail stack never contain the password). */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getClass().getSimpleName() + ": " + root.getMessage();
        return message.replaceAll("[\\r\\n]+", " ");
    }

    static String mask(String address) {
        int at = address == null ? -1 : address.indexOf('@');
        return at <= 1 ? "***" : address.charAt(0) + "***" + address.substring(at);
    }
}

package com.nexus360.admin;

import com.nexus360.NexusApplication;
import com.nexus360.ai.LlmProvider;
import com.nexus360.config.AppProperties;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.sla.SlaScheduler;
import com.nexus360.user.UserRepository;
import com.nexus360.workflow.WorkflowExecution;
import com.nexus360.workflow.WorkflowExecutionRepository;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Operational health for administrators. Nothing secret is returned (no keys, URLs or credentials). */
@Service
@RequiredArgsConstructor
public class SystemHealthService {

    public record DatabaseHealth(String status, Long latencyMs, String schemaVersion) {}

    public record RedisHealth(boolean enabled, String status) {}

    public record SchedulerHealth(boolean enabled, Instant lastRunAt) {}

    public record WorkflowHealth(long pendingRetries, long failedLast24h) {}

    public record AiHealth(String provider, boolean keyConfigured) {}

    public record Counts(long users, long incidents, long activeIncidents) {}

    /**
     * {@code status}: CONFIGURED (SMTP set) or NOT_CONFIGURED (messages are recorded as SKIPPED, nothing is sent);
     * {@code pending}: messages waiting for delivery; {@code failedLast24h}: messages that gave up after all retries.
     */
    public record EmailHealth(boolean configured, String status, long pending, long failedLast24h, Instant lastSentAt) {}

    /** {@code status}: UP, DEGRADED (an enabled optional component is down) or DOWN (database unavailable). */
    public record SystemHealth(String status, String version, long uptimeSeconds, DatabaseHealth database, RedisHealth redis,
                               SchedulerHealth slaScheduler, WorkflowHealth workflowEngine, AiHealth ai, Counts counts, EmailHealth email,
                               SchedulerHealth reportScheduler) {}

    private final JdbcTemplate jdbc;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final SlaScheduler slaScheduler;
    private final WorkflowExecutionRepository executions;
    private final UserRepository users;
    private final IncidentRepository incidents;
    private final LlmProvider llm;
    private final com.nexus360.email.MailTransport mail;
    private final com.nexus360.email.EmailOutboxRepository outbox;
    private final com.nexus360.report.ReportScheduler reportScheduler;
    private final AppProperties properties;
    private final Clock clock;

    public SystemHealth health() {
        DatabaseHealth database;
        try {
            long started = System.nanoTime();
            jdbc.queryForObject("select 1", Integer.class);
            long latency = (System.nanoTime() - started) / 1_000_000;
            List<String> versions = jdbc.queryForList(
                    "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1", String.class);
            database = new DatabaseHealth("UP", latency, versions.isEmpty() ? null : versions.get(0));
        } catch (RuntimeException e) {
            database = new DatabaseHealth("DOWN", null, null);
        }
        boolean redisEnabled = properties.redis() != null && properties.redis().enabled();
        String redisStatus = "DISABLED";
        if (redisEnabled) {
            try {
                StringRedisTemplate template = redis.getIfAvailable();
                if (template == null || template.getConnectionFactory() == null) {
                    redisStatus = "DOWN";
                } else {
                    try (RedisConnection connection = template.getConnectionFactory().getConnection()) {
                        redisStatus = "PONG".equalsIgnoreCase(connection.ping()) ? "UP" : "DOWN";
                    }
                }
            } catch (RuntimeException e) {
                redisStatus = "DOWN";
            }
        }
        boolean dbUp = "UP".equals(database.status());
        WorkflowHealth workflow = dbUp ? new WorkflowHealth(executions.countByStatus(WorkflowExecution.Status.RETRY_SCHEDULED),
                executions.countByStatusAndStartedAtAfter(WorkflowExecution.Status.FAILED, clock.instant().minus(Duration.ofHours(24))))
                : new WorkflowHealth(0, 0);
        Counts counts = dbUp ? new Counts(users.count(), incidents.count(), incidents.countByStatusIn(IncidentStatus.ACTIVE)) : new Counts(0, 0, 0);
        String status = !dbUp ? "DOWN" : redisEnabled && !"UP".equals(redisStatus) ? "DEGRADED" : "UP";
        boolean schedulerEnabled = properties.sla() != null && properties.sla().schedulerEnabled();
        EmailHealth email = new EmailHealth(mail.configured(), mail.configured() ? "CONFIGURED" : "NOT_CONFIGURED",
                dbUp ? outbox.countByStatus(com.nexus360.email.EmailMessage.Status.PENDING) : 0,
                dbUp ? outbox.countByStatusAndUpdatedAtAfter(com.nexus360.email.EmailMessage.Status.FAILED, clock.instant().minus(Duration.ofHours(24))) : 0,
                dbUp ? outbox.findFirstByStatusOrderBySentAtDesc(com.nexus360.email.EmailMessage.Status.SENT)
                        .map(com.nexus360.email.EmailMessage::getSentAt).orElse(null) : null);
        return new SystemHealth(status, NexusApplication.VERSION, ManagementFactory.getRuntimeMXBean().getUptime() / 1000, database,
                new RedisHealth(redisEnabled, redisStatus), new SchedulerHealth(schedulerEnabled, slaScheduler.lastRunAt()), workflow,
                new AiHealth(llm.name(), properties.ai().hasApiKey()), counts, email,
                new SchedulerHealth(reportScheduler.enabled(), reportScheduler.lastRunAt()));
    }
}

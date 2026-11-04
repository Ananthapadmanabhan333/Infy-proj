package com.nexus360.workflow;

import com.nexus360.config.AppProperties;
import com.nexus360.events.DomainEvents.IncidentEvent;
import com.nexus360.events.DomainEvents.IncidentEventType;
import com.nexus360.events.DomainEvents.ServiceRequestEvent;
import com.nexus360.events.DomainEvents.SlaAlertEvent;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.servicedesk.ServiceRequest;
import com.nexus360.servicedesk.ServiceRequestRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import com.nexus360.workflow.WorkflowModel.ActionDef;
import com.nexus360.workflow.WorkflowModel.Branch;
import com.nexus360.workflow.WorkflowModel.Definition;
import com.nexus360.workflow.WorkflowModel.StepLog;
import com.nexus360.workflow.WorkflowModel.Trigger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs active workflows for domain events after the originating transaction commits, and SCHEDULED workflows on a timer.
 *
 * <ul>
 *   <li>Conditions are evaluated against the current state of the record. A match runs the actions ("Yes" branch); a
 *       non-match runs the else-actions when there are any ("No" branch), otherwise it leaves no trace.</li>
 *   <li>Each action runs in its own transaction, so a failure rolls back only that action.</li>
 *   <li>Failures are retried with exponential backoff; a retry resumes at the first action that has not succeeded. A final
 *       failure notifies the workflow's creator and the system administrators.</li>
 *   <li>Limits: executions per workflow per hour, actions per execution, and the depth of workflow-triggered events.</li>
 *   <li>SCHEDULED workflows evaluate up to {@code app.workflow.scheduled-max-incidents} active incidents per run; each
 *       incident is processed at most once per schedule interval.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowEngine {

    private static final long MAX_BACKOFF_SECONDS = 3600;
    private static final long SCHEDULER_LOCK_KEY = 360_002L;
    private static final int DEFAULT_SCHEDULE_MINUTES = 60;

    private final WorkflowRepository workflows;
    private final WorkflowVersionRepository versions;
    private final WorkflowExecutionRepository executions;
    private final IncidentRepository incidents;
    private final ServiceRequestRepository requests;
    private final com.nexus360.change.ChangeRepository changes;
    private final com.nexus360.alert.AlertRepository alerts;
    private final UserRepository users;
    private final WorkflowFactSource factSource;
    private final ActionExecutor executor;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final Clock clock;

    private final java.util.Map<Long, Long> scheduleCursor = new java.util.concurrent.ConcurrentHashMap<>();

    private record Plan(Definition definition, Branch branch, String workflowName, Long creatorId, int maxRetries, int backoffSeconds,
                        Long incidentId, Long requestId, Long changeId, Long alertId, int depth, int attempt, Set<Integer> completed) {}

    /** Outcome of trying to start one execution. */
    private record Start(Long executionId, boolean rateLimited) {
        static final Start NONE = new Start(null, false);
    }

    // ------------------------------------------------------------------ event entry points

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentEvent(IncidentEvent event) {
        Trigger trigger = switch (event.type()) {
            case CREATED -> Trigger.INCIDENT_CREATED;
            case UPDATED -> Trigger.INCIDENT_UPDATED;
            case STATUS_CHANGED -> Trigger.INCIDENT_STATUS_CHANGED;
            case ASSIGNED -> Trigger.INCIDENT_ASSIGNED;
            default -> null;
        };
        if (trigger != null) {
            dispatch(trigger, event.incidentId(), null, event.depth());
        }
        if (event.type() == IncidentEventType.UPDATED && event.changes().containsKey("priority")) {
            dispatch(Trigger.PRIORITY_CHANGED, event.incidentId(), null, event.depth());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlaAlert(SlaAlertEvent event) {
        Trigger trigger = switch (event.type()) {
            case AT_RISK -> Trigger.SLA_AT_RISK;
            case BREACHED -> Trigger.SLA_BREACHED;
            default -> null;
        };
        if (trigger != null) {
            dispatch(trigger, event.incidentId(), null, event.depth());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onServiceRequest(ServiceRequestEvent event) {
        if ("CREATED".equals(event.type())) {
            dispatch(Trigger.SERVICE_REQUEST_CREATED, null, event.requestId(), event.depth());
        }
    }

    /** Release 3: CHANGE_REQUEST_CREATED when a change is raised. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChange(com.nexus360.events.DomainEvents.ChangeEvent event) {
        if ("CREATED".equals(event.type())) {
            dispatch(Trigger.CHANGE_REQUEST_CREATED, null, null, event.changeId(), event.depth());
        }
    }

    /** Release 3: ALERT_RECEIVED for every new or reopened, unsuppressed alert. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlert(com.nexus360.events.DomainEvents.AlertEvent event) {
        if ("RECEIVED".equals(event.type())) {
            dispatch(Trigger.ALERT_RECEIVED, null, null, null, event.alertId(), event.depth());
        }
    }

    /** Runs every matching active workflow for the trigger. Returns the ids of executions that were started. */
    public List<Long> dispatch(Trigger trigger, Long incidentId, Long requestId, int depth) {
        return dispatch(trigger, incidentId, requestId, null, depth);
    }

    /** Runs every matching active workflow for the trigger on an incident, a service request or a change. */
    public List<Long> dispatch(Trigger trigger, Long incidentId, Long requestId, Long changeId, int depth) {
        return dispatch(trigger, incidentId, requestId, changeId, null, depth);
    }

    /** Runs every matching active workflow for the trigger on an incident, a service request, a change or an alert. */
    public List<Long> dispatch(Trigger trigger, Long incidentId, Long requestId, Long changeId, Long alertId, int depth) {
        if (depth >= properties.workflow().maxDepth()) {
            log.warn("Skipping {} workflows for incident={} request={} change={}: event depth {} reached the limit of {}",
                    trigger, incidentId, requestId, changeId, depth, properties.workflow().maxDepth());
            return List.of();
        }
        List<Long> started = new ArrayList<>();
        List<Long> candidates = tx.execute(s -> workflows.findByTriggerEventAndActiveTrue(trigger).stream().map(Workflow::getId).toList());
        for (Long workflowId : candidates == null ? List.<Long>of() : candidates) {
            try {
                Start start = start(workflowId, trigger, incidentId, requestId, changeId, alertId, depth);
                if (start.executionId() != null) {
                    started.add(start.executionId());
                    runActions(start.executionId());
                }
            } catch (RuntimeException ex) {
                log.error("Workflow {} failed to start for {}", workflowId, trigger, ex);
            }
        }
        return started;
    }

    // ------------------------------------------------------------------ scheduled workflows

    @Scheduled(fixedDelayString = "${app.workflow.schedule-poll-interval-ms:60000}", initialDelay = 30_000)
    public void pollScheduled() {
        if (!properties.workflow().schedulerEnabled()) {
            return;
        }
        try {
            runDueScheduled();
        } catch (RuntimeException ex) {
            log.error("Scheduled workflow processing failed", ex);
        }
    }

    /**
     * Claims every active SCHEDULED workflow whose interval has elapsed (one instance at a time, via an advisory lock)
     * and processes it. Returns the number of executions started.
     */
    public int runDueScheduled() {
        Instant now = clock.instant();
        List<Long> due = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, SCHEDULER_LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return List.<Long>of();
            }
            List<Long> ids = new ArrayList<>();
            for (Workflow wf : workflows.findByTriggerEventAndActiveTrue(Trigger.SCHEDULED)) {
                int interval = intervalMinutes(wf);
                if (wf.getLastScheduledRunAt() == null || !wf.getLastScheduledRunAt().plus(Duration.ofMinutes(interval)).isAfter(now)) {
                    wf.setLastScheduledRunAt(now);
                    ids.add(wf.getId());
                }
            }
            return ids;
        });
        int started = 0;
        for (Long id : due == null ? List.<Long>of() : due) {
            started += processScheduled(id).size();
        }
        return started;
    }

    /**
     * Evaluates one SCHEDULED workflow against active incidents that it has not processed within its interval.
     * Stops at the per-hour execution limit (recording one rate-limited execution).
     */
    public List<Long> processScheduled(Long workflowId) {
        Workflow wf = tx.execute(s -> workflows.findById(workflowId).filter(w -> w.isActive() && w.getTriggerEvent() == Trigger.SCHEDULED)
                .orElse(null));
        if (wf == null) {
            return List.of();
        }
        Instant since = clock.instant().minus(Duration.ofMinutes(intervalMinutes(wf)));
        int max = Math.max(1, properties.workflow().scheduledMaxIncidents());
        // Round-robin over active incidents (by id) so that, with more active incidents than the per-run maximum,
        // every incident is evaluated in turn.
        long cursor = scheduleCursor.getOrDefault(workflowId, 0L);
        String sql = """
                select i.id from incidents i
                where i.status in """ + " " + IncidentStatus.activeSqlList() + """

                  and not exists (select 1 from workflow_executions e where e.workflow_id = ? and e.incident_id = i.id
                                  and e.trigger_event = 'SCHEDULED' and e.status <> 'RATE_LIMITED' and e.started_at > ?)
                """;
        List<Long> candidates = new ArrayList<>(jdbc.queryForList(sql + " and i.id > ? order by i.id asc limit ?", Long.class,
                workflowId, Timestamp.from(since), cursor, max));
        if (candidates.size() < max && cursor > 0) {
            candidates.addAll(jdbc.queryForList(sql + " and i.id <= ? order by i.id asc limit ?", Long.class,
                    workflowId, Timestamp.from(since), cursor, max - candidates.size()));
        }
        scheduleCursor.put(workflowId, candidates.size() >= max ? candidates.get(candidates.size() - 1) : 0L);
        List<Long> started = new ArrayList<>();
        for (Long incidentId : candidates) {
            try {
                Start start = start(workflowId, Trigger.SCHEDULED, incidentId, null, null, null, 0);
                if (start.rateLimited()) {
                    break;
                }
                if (start.executionId() != null) {
                    started.add(start.executionId());
                    runActions(start.executionId());
                }
            } catch (RuntimeException ex) {
                log.error("Scheduled workflow {} failed for incident {}", workflowId, incidentId, ex);
            }
        }
        return started;
    }

    private int intervalMinutes(Workflow wf) {
        Definition d = versions.findByWorkflowIdAndVersionNumber(wf.getId(), wf.getCurrentVersion()).map(WorkflowVersion::getDefinition).orElse(null);
        return d == null || d.schedule() == null ? DEFAULT_SCHEDULE_MINUTES : Math.max(15, d.schedule().intervalMinutes());
    }

    // ------------------------------------------------------------------ execution

    private Start start(Long workflowId, Trigger trigger, Long incidentId, Long requestId, Long changeId, Long alertId, int depth) {
        Start result = tx.execute(status -> {
            Workflow wf = workflows.findById(workflowId).filter(Workflow::isActive).orElse(null);
            if (wf == null) {
                return Start.NONE;
            }
            WorkflowVersion version = versions.findByWorkflowIdAndVersionNumber(wf.getId(), wf.getCurrentVersion()).orElse(null);
            Map<String, String> facts = factSource.facts(incidentId, requestId, changeId, alertId);
            if (version == null || facts == null) {
                return Start.NONE;
            }
            Definition definition = version.getDefinition();
            boolean matched = ConditionEvaluator.evaluate(definition.conditions(), facts).matched();
            if (!matched && definition.elseActions().isEmpty()) {
                return Start.NONE;
            }
            Instant now = clock.instant();
            WorkflowExecution execution = new WorkflowExecution();
            execution.setWorkflow(wf);
            execution.setWorkflowVersion(version.getVersionNumber());
            execution.setTriggerEvent(trigger);
            execution.setIncidentId(incidentId);
            execution.setServiceRequestId(requestId);
            execution.setChangeId(changeId);
            execution.setAlertId(alertId);
            execution.setDepth(depth);
            execution.setStartedAt(now);
            execution.setBranch(matched ? Branch.YES : Branch.NO);
            long recent = executions.countByWorkflowIdAndStartedAtAfter(wf.getId(), now.minus(Duration.ofHours(1)));
            if (recent >= wf.getMaxExecutionsPerHour()) {
                execution.setStatus(WorkflowExecution.Status.RATE_LIMITED);
                execution.setErrorMessage("Execution limit of " + wf.getMaxExecutionsPerHour() + " per hour reached; actions were not run");
                execution.setFinishedAt(now);
                executions.save(execution);
                log.warn("Workflow '{}' rate limited ({} executions in the last hour)", wf.getName(), recent);
                return new Start(null, true);
            }
            execution.setStatus(WorkflowExecution.Status.RUNNING);
            executions.save(execution);
            return new Start(execution.getId(), false);
        });
        return result == null ? Start.NONE : result;
    }

    /** Executes the remaining actions of a RUNNING execution, on the branch it was started with. */
    void runActions(Long executionId) {
        Plan plan = tx.execute(status -> {
            WorkflowExecution e = executions.findById(executionId).orElseThrow();
            Workflow wf = e.getWorkflow();
            Definition definition = versions.findByWorkflowIdAndVersionNumber(wf.getId(), e.getWorkflowVersion()).orElseThrow().getDefinition();
            Set<Integer> completed = new HashSet<>();
            e.getSteps().stream().filter(s -> "SUCCEEDED".equals(s.status()) || "SKIPPED".equals(s.status()))
                    .forEach(s -> completed.add(s.index()));
            return new Plan(definition, e.getBranch(), wf.getName(), wf.getCreatedBy() == null ? null : wf.getCreatedBy().getId(),
                    wf.getMaxRetries(), wf.getRetryBackoffSeconds(), e.getIncidentId(), e.getServiceRequestId(), e.getChangeId(), e.getAlertId(), e.getDepth(), e.getAttempt(),
                    completed);
        });
        if (plan == null) {
            return;
        }
        Actor actor = Actor.workflow(plan.workflowName(), plan.depth() + 1);
        List<ActionDef> actions = plan.definition().branch(plan.branch());
        int limit = Math.min(actions.size(), properties.workflow().maxActions());
        for (int index = 0; index < limit; index++) {
            if (plan.completed().contains(index)) {
                continue;
            }
            ActionDef action = actions.get(index);
            try {
                String message = tx.execute(status -> executor.execute(action, context(plan.incidentId(), plan.requestId(), plan.changeId(), plan.alertId(),
                        actor)));
                append(executionId, new StepLog(index, action.type().name(), "SUCCEEDED", message, clock.instant()));
            } catch (ActionExecutor.SkipException skip) {
                append(executionId, new StepLog(index, action.type().name(), "SKIPPED", skip.getMessage(), clock.instant()));
            } catch (RuntimeException ex) {
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                append(executionId, new StepLog(index, action.type().name(), "FAILED", reason, clock.instant()));
                scheduleRetryOrFail(executionId, plan, action, reason);
                return;
            }
        }
        if (actions.size() > limit) {
            append(executionId, new StepLog(limit, "LIMIT", "SKIPPED",
                    (actions.size() - limit) + " action(s) not run: limit of " + limit + " actions per execution", clock.instant()));
        }
        tx.executeWithoutResult(status -> executions.findById(executionId).ifPresent(e -> {
            e.setStatus(WorkflowExecution.Status.SUCCEEDED);
            e.setErrorMessage(null);
            e.setNextAttemptAt(null);
            e.setFinishedAt(clock.instant());
        }));
    }

    private void scheduleRetryOrFail(Long executionId, Plan plan, ActionDef action, String reason) {
        tx.executeWithoutResult(status -> executions.findById(executionId).ifPresent(e -> {
            Instant now = clock.instant();
            e.setErrorMessage(action.type() + " failed on attempt " + e.getAttempt() + ": " + reason);
            if (e.getAttempt() <= plan.maxRetries()) {
                long delay = Math.min(MAX_BACKOFF_SECONDS, plan.backoffSeconds() * (1L << Math.min(e.getAttempt() - 1, 10)));
                e.setStatus(WorkflowExecution.Status.RETRY_SCHEDULED);
                e.setNextAttemptAt(now.plusSeconds(delay));
            } else {
                e.setStatus(WorkflowExecution.Status.FAILED);
                e.setNextAttemptAt(null);
                e.setFinishedAt(now);
                Set<Long> recipients = new LinkedHashSet<>();
                if (plan.creatorId() != null) {
                    recipients.add(plan.creatorId());
                }
                users.findByActiveTrueAndRoleIn(List.of(Role.SYSTEM_ADMIN)).stream().map(User::getId).forEach(recipients::add);
                notifications.notifyUsers(recipients, "WORKFLOW_FAILED", "Workflow failed: " + plan.workflowName(),
                        e.getErrorMessage(), "/automation?tab=history", Severity.WARNING);
            }
        }));
    }

    @Scheduled(fixedDelayString = "${app.workflow.retry-poll-interval-ms:15000}", initialDelay = 15_000)
    public void processDueRetries() {
        try {
            List<Long> due = tx.execute(s -> executions.findDueRetries(clock.instant(), PageRequest.of(0, 20)));
            for (Long id : due == null ? List.<Long>of() : due) {
                Integer claimed = tx.execute(s -> executions.claimRetry(id));
                if (claimed != null && claimed == 1) {
                    runActions(id);
                }
            }
        } catch (RuntimeException ex) {
            log.error("Workflow retry processing failed", ex);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void append(Long executionId, StepLog step) {
        tx.executeWithoutResult(status -> executions.findById(executionId).ifPresent(e -> {
            List<StepLog> steps = new ArrayList<>(e.getSteps());
            steps.add(step);
            e.setSteps(steps);
        }));
    }

    private ActionExecutor.Context context(Long incidentId, Long requestId, Long changeId, Long alertId, Actor actor) {
        Incident incident = incidentId == null ? null : incidents.findDetailedById(incidentId).orElseThrow(
                () -> new IllegalStateException("Incident " + incidentId + " no longer exists"));
        ServiceRequest request = requestId == null ? null : requests.findWithDetailsById(requestId).orElseThrow(
                () -> new IllegalStateException("Service request " + requestId + " no longer exists"));
        com.nexus360.change.Change change = changeId == null ? null : changes.findWithDetailsById(changeId).orElseThrow(
                () -> new IllegalStateException("Change " + changeId + " no longer exists"));
        com.nexus360.alert.Alert alert = alertId == null ? null : alerts.findWithDetailsById(alertId).orElseThrow(
                () -> new IllegalStateException("Alert " + alertId + " no longer exists"));
        return new ActionExecutor.Context(incident, request, change, alert, actor);
    }
}

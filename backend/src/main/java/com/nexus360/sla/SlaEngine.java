package com.nexus360.sla;

import com.nexus360.admin.SettingsService;
import com.nexus360.events.DomainEvents.SlaAlertEvent;
import com.nexus360.events.DomainEvents.SlaAlertType;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentEscalation;
import com.nexus360.incident.IncidentEscalationRepository;
import com.nexus360.incident.IncidentTimeline;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.sla.IncidentSla.ClockStatus;
import com.nexus360.sla.IncidentSla.Health;
import com.nexus360.sla.IncidentSla.Outcome;
import com.nexus360.user.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SLA lifecycle: start, response, pause/resume (PENDING), completion, priority recalculation and the periodic
 * evaluation that detects at-risk and breached clocks and escalates them.
 *
 * <p>Escalation levels: 1 = at risk or response missed (assignee / team warned), 2 = resolution breached
 * (department manager), 3 = breached by more than the configured overrun (managers and system administrators).
 *
 * <p>Evaluation is idempotent: every (SLA clock, level) escalation is recorded once in {@code incident_escalations}
 * (unique index), and each alert is guarded by a persisted marker (response-breached flag, {@code at_risk_at},
 * {@code breached_at}), so re-running the evaluator never re-escalates or re-notifies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaEngine {

    public record Snapshot(String policyName, boolean businessHoursOnly, ClockStatus clockStatus, Health health, Outcome outcome,
                           Instant responseDueAt, Instant respondedAt, boolean responseBreached, Instant resolutionDueAt,
                           long remainingSeconds, double percentElapsed, int escalationLevel) {}

    public record EvaluationResult(int evaluated, int atRisk, int breached, int escalated) {}

    private final IncidentSlaRepository slas;
    private final SlaPolicyRepository policies;
    private final SlaEventRepository events;
    private final IncidentTimeline timeline;
    private final IncidentEscalationRepository escalations;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    // ------------------------------------------------------------------ lifecycle

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<IncidentSla> start(Incident incident, Instant startAt) {
        Optional<IncidentSla> existing = slas.findByIncidentId(incident.getId());
        if (existing.isPresent()) {
            return existing;
        }
        SlaPolicy policy = policies.findFirstByPriorityAndActiveTrue(incident.getPriority()).orElse(null);
        if (policy == null) {
            log.warn("No active SLA policy for priority {}; incident {} has no SLA", incident.getPriority(), incident.getNumber());
            return Optional.empty();
        }
        IncidentSla sla = new IncidentSla();
        sla.setIncident(incident);
        applyPolicy(sla, policy);
        sla.setStartedAt(startAt);
        sla.setRunningSince(startAt);
        sla.setClockStatus(ClockStatus.RUNNING);
        sla.setHealth(Health.ON_TRACK);
        BusinessCalendarSpec spec = spec(sla);
        sla.setResponseDueAt(BusinessTimeCalculator.addBusinessSeconds(startAt, sla.getResponseTargetMinutes() * 60L, spec));
        sla.setResolutionDueAt(BusinessTimeCalculator.addBusinessSeconds(startAt, sla.resolutionTargetSeconds(), spec));
        slas.save(sla);
        event(sla, "STARTED", policy.getName() + ": respond within " + human(sla.getResponseTargetMinutes())
                + ", resolve within " + human(sla.getResolutionTargetMinutes())
                + (sla.isBusinessHoursOnly() ? " (business hours)" : " (24x7)"), startAt);
        return Optional.of(sla);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordResponse(Incident incident, Instant at) {
        slas.findByIncidentId(incident.getId()).ifPresent(sla -> {
            if (sla.getRespondedAt() != null || sla.getClockStatus() == ClockStatus.STOPPED) {
                return;
            }
            sla.setRespondedAt(at);
            boolean late = at.isAfter(sla.getResponseDueAt());
            sla.setResponseBreached(late);
            event(sla, "RESPONDED", late ? "First response after the response target" : "First response within target", at);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void pause(Incident incident, Instant at, String reason) {
        slas.findByIncidentId(incident.getId()).ifPresent(sla -> {
            if (sla.getClockStatus() != ClockStatus.RUNNING) {
                return;
            }
            sla.setConsumedSeconds(sla.getConsumedSeconds() + BusinessTimeCalculator.businessSecondsBetween(sla.getRunningSince(), at, spec(sla)));
            sla.setRunningSince(null);
            sla.setPausedAt(at);
            sla.setResolutionDueAt(null);
            sla.setClockStatus(ClockStatus.PAUSED);
            event(sla, "PAUSED", reason == null ? "Clock paused" : "Clock paused: " + reason, at);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resume(Incident incident, Instant at) {
        slas.findByIncidentId(incident.getId()).ifPresent(sla -> {
            if (sla.getClockStatus() != ClockStatus.PAUSED) {
                return;
            }
            if (sla.getPausedAt() != null) {
                sla.setTotalPausedSeconds(sla.getTotalPausedSeconds() + Math.max(0, Duration.between(sla.getPausedAt(), at).getSeconds()));
            }
            sla.setPausedAt(null);
            startRunning(sla, at);
            event(sla, "RESUMED", "Clock resumed with " + human(remainingMinutes(sla)) + " remaining", at);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Incident incident, Instant at) {
        slas.findByIncidentId(incident.getId()).ifPresent(sla -> {
            if (sla.getClockStatus() == ClockStatus.STOPPED) {
                return;
            }
            if (sla.getClockStatus() == ClockStatus.RUNNING) {
                sla.setConsumedSeconds(sla.getConsumedSeconds() + BusinessTimeCalculator.businessSecondsBetween(sla.getRunningSince(), at, spec(sla)));
            }
            if (sla.getRespondedAt() == null) {
                sla.setRespondedAt(at);
                sla.setResponseBreached(at.isAfter(sla.getResponseDueAt()));
            }
            boolean met = sla.getConsumedSeconds() <= sla.resolutionTargetSeconds();
            sla.setRunningSince(null);
            sla.setPausedAt(null);
            sla.setClockStatus(ClockStatus.STOPPED);
            sla.setOutcome(met ? Outcome.MET : Outcome.MISSED);
            sla.setCompletedAt(at);
            event(sla, met ? "MET" : "MISSED", (met ? "Resolved within target" : "Resolved after target")
                    + " (" + human(sla.getConsumedSeconds() / 60) + " of " + human(sla.getResolutionTargetMinutes()) + ")", at);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(Incident incident, Instant at, String reason) {
        slas.findByIncidentId(incident.getId()).ifPresent(sla -> {
            if (sla.getClockStatus() == ClockStatus.STOPPED) {
                return;
            }
            sla.setRunningSince(null);
            sla.setPausedAt(null);
            sla.setResolutionDueAt(null);
            sla.setClockStatus(ClockStatus.STOPPED);
            sla.setOutcome(Outcome.CANCELLED);
            sla.setCompletedAt(at);
            event(sla, "CANCELLED", reason, at);
        });
    }

    /** Reopened incident, or a child unlinked from its parent: the clock continues from the time already consumed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reopen(Incident incident, Instant at, String reason) {
        Optional<IncidentSla> found = slas.findByIncidentId(incident.getId());
        if (found.isEmpty()) {
            start(incident, at);
            return;
        }
        IncidentSla sla = found.get();
        if (sla.getClockStatus() != ClockStatus.STOPPED) {
            return;
        }
        sla.setOutcome(null);
        sla.setCompletedAt(null);
        startRunning(sla, at);
        event(sla, "RESUMED", reason, at);
    }

    /** Priority changed: switch to the matching policy keeping the business time already consumed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recalculate(Incident incident, Instant at) {
        Optional<IncidentSla> found = slas.findByIncidentId(incident.getId());
        if (found.isEmpty()) {
            if (incident.getStatus().isActive() && incident.getParent() == null) {
                start(incident, at);
            }
            return;
        }
        IncidentSla sla = found.get();
        if (sla.getClockStatus() == ClockStatus.STOPPED || sla.getPriority() == incident.getPriority()) {
            return;
        }
        SlaPolicy policy = policies.findFirstByPriorityAndActiveTrue(incident.getPriority()).orElse(null);
        if (policy == null) {
            return;
        }
        String previous = sla.getPolicyName();
        if (sla.getClockStatus() == ClockStatus.RUNNING) {
            sla.setConsumedSeconds(sla.getConsumedSeconds() + BusinessTimeCalculator.businessSecondsBetween(sla.getRunningSince(), at, spec(sla)));
            sla.setRunningSince(at);
        }
        applyPolicy(sla, policy);
        BusinessCalendarSpec spec = spec(sla);
        if (sla.getRespondedAt() == null) {
            sla.setResponseDueAt(BusinessTimeCalculator.addBusinessSeconds(sla.getStartedAt(), sla.getResponseTargetMinutes() * 60L, spec));
        }
        if (sla.getClockStatus() == ClockStatus.RUNNING) {
            sla.setResolutionDueAt(BusinessTimeCalculator.addBusinessSeconds(at, Math.max(0, sla.resolutionTargetSeconds() - sla.getConsumedSeconds()), spec));
        }
        Snapshot snap = snapshot(sla, at);
        if (snap.health() == Health.ON_TRACK && sla.getHealth() != Health.ON_TRACK) {
            sla.setHealth(Health.ON_TRACK);
        }
        event(sla, "RECALCULATED", "Policy changed from " + previous + " to " + policy.getName(), at);
    }

    // ------------------------------------------------------------------ evaluation

    /** Live view of an SLA. Health is computed from the clock, so it is accurate between scheduler runs. */
    public Snapshot snapshot(IncidentSla sla, Instant now) {
        long consumed = sla.getConsumedSeconds();
        if (sla.getClockStatus() == ClockStatus.RUNNING && sla.getRunningSince() != null) {
            consumed += BusinessTimeCalculator.businessSecondsBetween(sla.getRunningSince(), now, spec(sla));
        }
        long target = sla.resolutionTargetSeconds();
        long remaining = target - consumed;
        double percent = target == 0 ? 100 : Math.round(consumed * 1000.0 / target) / 10.0;
        Health health = sla.getHealth();
        if (sla.getClockStatus() != ClockStatus.STOPPED) {
            health = remaining < 0 ? Health.BREACHED : percent >= sla.getAtRiskPercent() ? Health.AT_RISK : Health.ON_TRACK;
        } else if (sla.getOutcome() == Outcome.MISSED) {
            health = Health.BREACHED;
        }
        return new Snapshot(sla.getPolicyName(), sla.isBusinessHoursOnly(), sla.getClockStatus(), health, sla.getOutcome(),
                sla.getResponseDueAt(), sla.getRespondedAt(), sla.isResponseBreached() || (sla.getRespondedAt() == null
                && sla.getClockStatus() != ClockStatus.STOPPED && now.isAfter(sla.getResponseDueAt())),
                sla.getResolutionDueAt(), remaining, percent, sla.getEscalationLevel());
    }

    @Transactional
    public EvaluationResult evaluate() {
        Instant now = clock.instant();
        int atRisk = 0;
        int breached = 0;
        int escalated = 0;
        List<SlaAlertEvent> alerts = new ArrayList<>();
        double severeOverrun = settings.getDouble(SettingsService.SLA_SEVERE_OVERRUN_PERCENT, 50);
        List<IncidentSla> running = slas.findByClockStatus(ClockStatus.RUNNING);
        for (IncidentSla sla : running) {
            Incident incident = sla.getIncident();
            Snapshot snap = snapshot(sla, now);
            String link = "/incidents/" + incident.getId();
            Long departmentId = incident.getDepartment() == null ? null : incident.getDepartment().getId();
            Long assigneeId = incident.getAssignee() == null ? null : incident.getAssignee().getId();

            if (sla.getRespondedAt() == null && !sla.isResponseBreached() && now.isAfter(sla.getResponseDueAt())) {
                sla.setResponseBreached(true);
                event(sla, "BREACHED", "Response target missed — nobody has responded yet", now);
                timeline.record(incident, Actor.SYSTEM, "SLA", "Response SLA missed: no response within " + human(sla.getResponseTargetMinutes()));
                raiseLevel(sla, incident, 1, "Response target of " + human(sla.getResponseTargetMinutes()) + " missed", now);
                notifications.notifyDepartment(departmentId, "SLA_RESPONSE_BREACHED", "Response overdue: " + incident.getNumber(),
                        incident.getTitle() + " has not been responded to within " + human(sla.getResponseTargetMinutes()) + ".", link, Severity.WARNING);
            }

            if (snap.health() == Health.AT_RISK && sla.getHealth() == Health.ON_TRACK && sla.getAtRiskAt() != null) {
                sla.setHealth(Health.AT_RISK); // already alerted for this clock (e.g. health reset by a priority change)
            } else if (snap.health() == Health.AT_RISK && sla.getHealth() == Health.ON_TRACK) {
                sla.setHealth(Health.AT_RISK);
                sla.setAtRiskAt(now);
                atRisk++;
                event(sla, "AT_RISK", snap.percentElapsed() + "% of the resolution target consumed", now);
                timeline.record(incident, Actor.SYSTEM, "SLA", "SLA at risk: " + human(Math.max(0, snap.remainingSeconds() / 60)) + " remaining");
                raiseLevel(sla, incident, 1, "Resolution SLA at risk (" + snap.percentElapsed() + "% consumed)", now);
                if (assigneeId != null) {
                    notifications.notifyUser(assigneeId, "SLA_AT_RISK", "SLA at risk: " + incident.getNumber(),
                            incident.getTitle() + " — " + human(Math.max(0, snap.remainingSeconds() / 60)) + " left to resolve.", link, Severity.WARNING);
                } else {
                    notifications.notifyDepartment(departmentId, "SLA_AT_RISK", "Unassigned incident at risk: " + incident.getNumber(),
                            incident.getTitle() + " is unassigned and close to breaching its SLA.", link, Severity.WARNING);
                }
                if (settings.getBoolean(SettingsService.SLA_AT_RISK_NOTIFY_MANAGERS, false)) {
                    notifications.notifyDepartmentManager(departmentId, "SLA_AT_RISK", "SLA at risk: " + incident.getNumber(),
                            incident.getTitle() + " — " + human(Math.max(0, snap.remainingSeconds() / 60)) + " left to resolve.", link, Severity.WARNING);
                }
                alerts.add(new SlaAlertEvent(SlaAlertType.AT_RISK, incident.getId(), sla.getEscalationLevel(), 0));
            }

            if (snap.health() == Health.BREACHED && sla.getHealth() != Health.BREACHED && sla.getBreachedAt() != null) {
                sla.setHealth(Health.BREACHED); // breach already escalated and notified for this clock
            } else if (snap.health() == Health.BREACHED && sla.getHealth() != Health.BREACHED) {
                sla.setHealth(Health.BREACHED);
                sla.setBreachedAt(now);
                breached++;
                event(sla, "BREACHED", "Resolution target of " + human(sla.getResolutionTargetMinutes()) + " exceeded", now);
                raiseLevel(sla, incident, 2, "Resolution target of " + human(sla.getResolutionTargetMinutes()) + " exceeded", now);
                event(sla, "ESCALATED", "Escalated to level 2 — department manager", now);
                timeline.record(incident, Actor.SYSTEM, "ESCALATED", "SLA breached — escalated to the department manager (level 2)");
                notifications.notifyDepartmentManager(departmentId, "SLA_BREACHED", "SLA breached: " + incident.getNumber(),
                        incident.getTitle() + " exceeded its " + human(sla.getResolutionTargetMinutes()) + " resolution target.", link, Severity.CRITICAL);
                notifications.notifyUser(assigneeId, "SLA_BREACHED", "SLA breached: " + incident.getNumber(),
                        "Your incident exceeded its resolution target and was escalated to your manager.", link, Severity.CRITICAL);
                alerts.add(new SlaAlertEvent(SlaAlertType.BREACHED, incident.getId(), sla.getEscalationLevel(), 0));
            }

            double overrun = sla.resolutionTargetSeconds() == 0 ? 0 : -snap.remainingSeconds() * 100.0 / sla.resolutionTargetSeconds();
            if (snap.health() == Health.BREACHED && overrun >= severeOverrun && sla.getEscalationLevel() < 3
                    && !alreadyEscalated(sla, 3)) {
                raiseLevel(sla, incident, 3, "Resolution overrun of " + Math.round(overrun) + "% beyond target", now);
                escalated++;
                event(sla, "ESCALATED", "Escalated to level 3 — overrun " + Math.round(overrun) + "% beyond target", now);
                timeline.record(incident, Actor.SYSTEM, "ESCALATED", "Severe SLA overrun — escalated to operations leadership (level 3)");
                notifications.notifyRoles(List.of(Role.SYSTEM_ADMIN, Role.IT_MANAGER, Role.INCIDENT_MANAGER), "SLA_ESCALATED",
                        "Level 3 escalation: " + incident.getNumber(),
                        incident.getTitle() + " is " + Math.round(overrun) + "% beyond its resolution target.", link, Severity.CRITICAL);
                alerts.add(new SlaAlertEvent(SlaAlertType.ESCALATED, incident.getId(), 3, 0));
            }
        }
        alerts.forEach(publisher::publishEvent);
        return new EvaluationResult(running.size(), atRisk, breached, escalated);
    }

    /** Demo-only: move a clock's reference points into the past so the real evaluator sees elapsed time. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void simulateElapsed(Incident incident, int minutes) {
        IncidentSla sla = slas.findByIncidentId(incident.getId()).orElseThrow(() -> new IllegalStateException("Incident has no SLA"));
        Duration shift = Duration.ofMinutes(minutes);
        sla.setStartedAt(sla.getStartedAt().minus(shift));
        sla.setResponseDueAt(sla.getResponseDueAt().minus(shift));
        if (sla.getRunningSince() != null) {
            sla.setRunningSince(sla.getRunningSince().minus(shift));
        }
        if (sla.getResolutionDueAt() != null) {
            sla.setResolutionDueAt(sla.getResolutionDueAt().minus(shift));
        }
        if (sla.getPausedAt() != null) {
            sla.setPausedAt(sla.getPausedAt().minus(shift));
        }
        event(sla, "SIMULATED", "Demo simulation: clock advanced by " + human(minutes), clock.instant());
    }

    // ------------------------------------------------------------------ helpers

    /** Raises the clock and incident to {@code level} and records the escalation once per (clock, level). */
    private void raiseLevel(IncidentSla sla, Incident incident, int level, String reason, Instant at) {
        if (sla.getEscalationLevel() < level) {
            sla.setEscalationLevel(level);
        }
        if (incident.getEscalationLevel() < level) {
            incident.setEscalationLevel(level);
        }
        if (!alreadyEscalated(sla, level)) {
            IncidentEscalation record = new IncidentEscalation();
            record.setIncidentId(incident.getId());
            record.setIncidentSlaId(sla.getId());
            record.setLevel(level);
            record.setSource(IncidentEscalation.Source.SLA);
            record.setReason(reason);
            record.setActorName(Actor.SYSTEM.name());
            record.setCreatedAt(at);
            escalations.save(record);
        }
    }

    private boolean alreadyEscalated(IncidentSla sla, int level) {
        return sla.getId() != null && escalations.existsByIncidentSlaIdAndLevel(sla.getId(), level);
    }

    private void startRunning(IncidentSla sla, Instant at) {
        sla.setRunningSince(at);
        sla.setClockStatus(ClockStatus.RUNNING);
        sla.setResolutionDueAt(BusinessTimeCalculator.addBusinessSeconds(at,
                Math.max(0, sla.resolutionTargetSeconds() - sla.getConsumedSeconds()), spec(sla)));
    }

    private void applyPolicy(IncidentSla sla, SlaPolicy policy) {
        sla.setPolicy(policy);
        sla.setPolicyName(policy.getName());
        sla.setPriority(policy.getPriority());
        sla.setBusinessHoursOnly(policy.isBusinessHoursOnly() && policy.getCalendar() != null);
        sla.setCalendar(policy.isBusinessHoursOnly() ? policy.getCalendar() : null);
        sla.setResponseTargetMinutes(policy.getResponseMinutes());
        sla.setResolutionTargetMinutes(policy.getResolutionMinutes());
        sla.setAtRiskPercent(policy.getAtRiskPercent());
    }

    static BusinessCalendarSpec spec(IncidentSla sla) {
        return sla.isBusinessHoursOnly() && sla.getCalendar() != null ? sla.getCalendar().toSpec() : null;
    }

    private long remainingMinutes(IncidentSla sla) {
        return Math.max(0, (sla.resolutionTargetSeconds() - sla.getConsumedSeconds()) / 60);
    }

    private void event(IncidentSla sla, String type, String details, Instant at) {
        SlaEvent event = new SlaEvent();
        event.setIncidentSla(sla);
        event.setEventType(type);
        event.setDetails(details == null ? null : details.length() > 500 ? details.substring(0, 499) : details);
        event.setCreatedAt(at);
        events.save(event);
    }

    static String human(long minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours < 48) {
            return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
        }
        return (hours / 24) + " d " + (hours % 24) + " h";
    }
}

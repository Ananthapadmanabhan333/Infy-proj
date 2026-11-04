package com.nexus360.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus360.admin.SettingsService;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentTimeline;
import com.nexus360.incident.Priority;
import com.nexus360.notification.Notification;
import com.nexus360.notification.NotificationService;
import com.nexus360.sla.IncidentSla.ClockStatus;
import com.nexus360.sla.IncidentSla.Health;
import com.nexus360.sla.IncidentSla.Outcome;
import com.nexus360.user.Department;
import com.nexus360.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** SLA lifecycle and escalation with mocked repositories and a controllable clock (24x7 P1 policy: 15 min / 240 min). */
class SlaEngineTest {

    private final IncidentSlaRepository slas = mock(IncidentSlaRepository.class);
    private final SlaPolicyRepository policies = mock(SlaPolicyRepository.class);
    private final SlaEventRepository events = mock(SlaEventRepository.class);
    private final IncidentTimeline timeline = mock(IncidentTimeline.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public Instant instant() { return now.get(); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    };
    private SlaEngine engine;
    private Incident incident;
    private final AtomicReference<IncidentSla> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        engine = new SlaEngine(slas, policies, events, timeline, mock(com.nexus360.incident.IncidentEscalationRepository.class), notifications,
                settings, publisher, clock);
        SlaPolicy p1 = new SlaPolicy();
        p1.setName("P1");
        p1.setPriority(Priority.CRITICAL);
        p1.setResponseMinutes(15);
        p1.setResolutionMinutes(240);
        p1.setAtRiskPercent(75);
        when(policies.findFirstByPriorityAndActiveTrue(Priority.CRITICAL)).thenReturn(Optional.of(p1));
        when(slas.findByIncidentId(anyLong())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(slas.save(any())).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(settings.getDouble(eq(SettingsService.SLA_SEVERE_OVERRUN_PERCENT), anyDouble())).thenReturn(50.0);
        incident = new Incident();
        incident.setId(42L);
        incident.setNumber("INC-42");
        incident.setTitle("Payroll down");
        incident.setPriority(Priority.CRITICAL);
        Department dept = new Department();
        dept.setId(7L);
        incident.setDepartment(dept);
        User agent = new User();
        agent.setId(99L);
        incident.setAssignee(agent);
    }

    private void advance(Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    void startComputesDeadlines() {
        IncidentSla sla = engine.start(incident, now.get()).orElseThrow();
        assertThat(sla.getResponseDueAt()).isEqualTo(now.get().plusSeconds(15 * 60));
        assertThat(sla.getResolutionDueAt()).isEqualTo(now.get().plusSeconds(240 * 60));
        assertThat(sla.getClockStatus()).isEqualTo(ClockStatus.RUNNING);
    }

    @Test
    void pauseExcludesOnHoldTimeAndResumeMovesDeadline() {
        engine.start(incident, now.get());
        advance(Duration.ofMinutes(60));
        engine.pause(incident, now.get(), "waiting for vendor");
        assertThat(stored.get().getConsumedSeconds()).isEqualTo(3600);
        assertThat(stored.get().getResolutionDueAt()).isNull();

        advance(Duration.ofMinutes(120)); // paused time does not count
        engine.resume(incident, now.get());
        assertThat(stored.get().getResolutionDueAt()).isEqualTo(now.get().plusSeconds(180 * 60));
        assertThat(stored.get().getTotalPausedSeconds()).isEqualTo(7200);
    }

    @Test
    void evaluationEscalatesThroughAtRiskBreachAndSevereOverrun() {
        engine.start(incident, now.get());
        IncidentSla sla = stored.get();
        sla.setIncident(incident);
        when(slas.findByClockStatus(ClockStatus.RUNNING)).thenReturn(List.of(sla));

        advance(Duration.ofMinutes(20)); // response target (15 min) missed
        engine.evaluate();
        assertThat(sla.isResponseBreached()).isTrue();
        assertThat(sla.getEscalationLevel()).isEqualTo(1);

        advance(Duration.ofMinutes(165)); // 185 of 240 min = 77% -> at risk
        SlaEngine.EvaluationResult atRisk = engine.evaluate();
        assertThat(atRisk.atRisk()).isEqualTo(1);
        assertThat(sla.getHealth()).isEqualTo(Health.AT_RISK);
        verify(notifications).notifyUser(eq(99L), eq("SLA_AT_RISK"), anyString(), anyString(), anyString(), eq(Notification.Severity.WARNING));

        advance(Duration.ofMinutes(60)); // 245 min -> breached, level 2
        SlaEngine.EvaluationResult breached = engine.evaluate();
        assertThat(breached.breached()).isEqualTo(1);
        assertThat(sla.getHealth()).isEqualTo(Health.BREACHED);
        assertThat(sla.getEscalationLevel()).isEqualTo(2);
        assertThat(incident.getEscalationLevel()).isEqualTo(2);
        verify(notifications).notifyDepartmentManager(eq(7L), eq("SLA_BREACHED"), anyString(), anyString(), anyString(),
                eq(Notification.Severity.CRITICAL));

        advance(Duration.ofMinutes(120)); // 365 min = 52% overrun -> level 3
        SlaEngine.EvaluationResult severe = engine.evaluate();
        assertThat(severe.escalated()).isEqualTo(1);
        assertThat(sla.getEscalationLevel()).isEqualTo(3);
    }

    @Test
    void completionRecordsMetOrMissed() {
        engine.start(incident, now.get());
        advance(Duration.ofMinutes(90));
        engine.complete(incident, now.get());
        assertThat(stored.get().getOutcome()).isEqualTo(Outcome.MET);
        assertThat(stored.get().getClockStatus()).isEqualTo(ClockStatus.STOPPED);

        stored.set(null);
        engine.start(incident, now.get());
        advance(Duration.ofMinutes(300));
        engine.complete(incident, now.get());
        assertThat(stored.get().getOutcome()).isEqualTo(Outcome.MISSED);
        assertThat(engine.snapshot(stored.get(), now.get()).health()).isEqualTo(Health.BREACHED);
    }
}

package com.nexus360.integration;

import com.nexus360.events.DomainEvents.IncidentEvent;
import com.nexus360.events.DomainEvents.SlaAlertEvent;
import com.nexus360.incident.IncidentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** Fans committed domain events out to enabled outbound integrations that subscribe to them. */
@Component
@RequiredArgsConstructor
public class IntegrationEventListener {

    public static final List<String> EVENTS = List.of("incident.created", "incident.status_changed", "incident.resolved",
            "incident.escalated", "sla.at_risk", "sla.breached");

    private static final List<Integration.Type> OUTBOUND = List.of(Integration.Type.WEBHOOK, Integration.Type.SLACK, Integration.Type.TEAMS);

    private final IntegrationRepository integrations;
    private final IntegrationDispatcher dispatcher;
    private final IncidentRepository incidents;
    private final TransactionTemplate tx;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncident(IncidentEvent event) {
        String name = switch (event.type()) {
            case CREATED -> "incident.created";
            case ESCALATED -> "incident.escalated";
            case STATUS_CHANGED -> "RESOLVED".equals(event.changes().get("to")) ? "incident.resolved" : "incident.status_changed";
            default -> null;
        };
        if (name != null) {
            send(name, event.incidentId(), event.changes());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSla(SlaAlertEvent event) {
        String name = switch (event.type()) {
            case AT_RISK -> "sla.at_risk";
            case BREACHED -> "sla.breached";
            default -> null;
        };
        if (name != null) {
            send(name, event.incidentId(), Map.of("escalationLevel", String.valueOf(event.escalationLevel())));
        }
    }

    private void send(String eventName, Long incidentId, Map<String, String> extra) {
        record Work(List<Long> targets, Map<String, Object> payload) {}
        Work work = tx.execute(s -> {
            List<Long> targets = integrations.findByEnabledTrueAndTypeIn(OUTBOUND).stream()
                    .filter(i -> i.eventList().contains(eventName)).map(Integration::getId).toList();
            if (targets.isEmpty()) {
                return null;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            incidents.findDetailedById(incidentId).ifPresent(i -> payload.put("incident", IntegrationDispatcher.incidentPayload(i)));
            payload.put("changes", extra);
            return new Work(targets, payload);
        });
        if (work != null) {
            work.targets().forEach(id -> dispatcher.deliver(id, eventName, work.payload()));
        }
    }
}

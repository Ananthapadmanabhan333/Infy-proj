package com.nexus360.workflow;

import com.nexus360.change.ChangeRepository;
import com.nexus360.change.ChangeService;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.servicedesk.ServiceRequestRepository;
import com.nexus360.sla.IncidentSla;
import com.nexus360.sla.IncidentSlaRepository;
import com.nexus360.sla.SlaEngine;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Loads the facts of a triggering record, including the live SLA remaining time. Call inside a transaction. */
@Component
@RequiredArgsConstructor
public class WorkflowFactSource {

    private final IncidentRepository incidents;
    private final ServiceRequestRepository requests;
    private final ChangeRepository changes;
    private final com.nexus360.alert.AlertRepository alerts;
    private final IncidentSlaRepository slas;
    private final SlaEngine slaEngine;
    private final Clock clock;

    public Map<String, String> facts(Long incidentId, Long requestId) {
        return facts(incidentId, requestId, null);
    }

    /** Facts of an incident, a service request or (Release 3) a change ({@code changeRisk}, {@code changeType}, ...). */
    public Map<String, String> facts(Long incidentId, Long requestId, Long changeId) {
        return facts(incidentId, requestId, changeId, null);
    }

    /** Facts of an incident, a service request, a change or (Release 3) an alert ({@code alertSeverity}, {@code alertSource}, ...). */
    public Map<String, String> facts(Long incidentId, Long requestId, Long changeId, Long alertId) {
        if (incidentId != null) {
            return incidents.findDetailedById(incidentId).map(this::facts).orElse(null);
        }
        if (requestId != null) {
            return requests.findWithDetailsById(requestId).map(WorkflowFacts::of).orElse(null);
        }
        if (changeId != null) {
            return changes.findWithDetailsById(changeId).map(ChangeService::facts).orElse(null);
        }
        if (alertId != null) {
            return alerts.findWithDetailsById(alertId).map(com.nexus360.alert.AlertExtensions::facts).orElse(null);
        }
        return null;
    }

    public Map<String, String> facts(Incident incident) {
        Long remaining = slas.findByIncidentId(incident.getId())
                .filter(s -> s.getClockStatus() != IncidentSla.ClockStatus.STOPPED)
                .map(s -> Math.floorDiv(slaEngine.snapshot(s, clock.instant()).remainingSeconds(), 60L))
                .orElse(null);
        return WorkflowFacts.of(incident, incidents.countByParentId(incident.getId()), remaining);
    }
}

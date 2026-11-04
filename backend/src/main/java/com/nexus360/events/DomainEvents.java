package com.nexus360.events;

import java.util.Map;

/**
 * Application events published after state changes. Workflows, integrations and notifications subscribe to
 * them after the originating transaction commits. {@code depth} counts how many workflow hops produced the
 * event and is used to stop automation loops.
 */
public final class DomainEvents {

    private DomainEvents() {}

    public enum IncidentEventType { CREATED, UPDATED, STATUS_CHANGED, ASSIGNED, ESCALATED, COMMENTED }

    public record IncidentEvent(IncidentEventType type, Long incidentId, Long actorId, int depth, Map<String, String> changes) {
        public IncidentEvent {
            changes = changes == null ? Map.of() : Map.copyOf(changes);
        }
    }

    public enum SlaAlertType { AT_RISK, BREACHED, ESCALATED }

    public record SlaAlertEvent(SlaAlertType type, Long incidentId, int escalationLevel, int depth) {}

    public record ServiceRequestEvent(Long requestId, String type, int depth) {}

    /** Release 3 change management events ({@code type} CREATED drives the CHANGE_REQUEST_CREATED workflow trigger). */
    public record ChangeEvent(Long changeId, String type, int depth) {}

    /** Release 3 alert events ({@code type} RECEIVED — a new or reopened, unsuppressed alert — drives the ALERT_RECEIVED workflow trigger). */
    public record AlertEvent(Long alertId, String type, int depth) {}

    /**
     * Release 3 BE-4: an incident was declared a major incident ({@code major} true) or the flag was cleared. Published synchronously
     * inside the incident transaction, so the major incident command record is created atomically with the flag
     * ({@code com.nexus360.major.MajorIncidentService}).
     */
    public record MajorIncidentEvent(Long incidentId, boolean major, Long actorId, String actorName, boolean automatic) {}
}

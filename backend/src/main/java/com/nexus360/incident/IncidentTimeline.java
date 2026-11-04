package com.nexus360.incident;

import com.nexus360.security.Actor;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Appends entries to an incident's activity history. */
@Component
@RequiredArgsConstructor
public class IncidentTimeline {

    private final IncidentHistoryRepository repository;

    public void record(Incident incident, Actor actor, String eventType, String message) {
        write(incident, actor, eventType, null, null, null, message, null);
    }

    public void change(Incident incident, Actor actor, String field, Object oldValue, Object newValue) {
        String oldText = oldValue == null ? null : oldValue.toString();
        String newText = newValue == null ? null : newValue.toString();
        if (java.util.Objects.equals(oldText, newText)) {
            return;
        }
        String type = "status".equals(field) ? "STATUS_CHANGED" : "assignee".equals(field) || "department".equals(field) || "team".equals(field) ? "ASSIGNED" : "FIELD_CHANGED";
        write(incident, actor, type, field, oldText, newText, null, null);
    }

    /** Historical entries for seeded data. */
    public void recordAt(Incident incident, Actor actor, String eventType, String message, Instant at) {
        write(incident, actor, eventType, null, null, null, message, at);
    }

    private void write(Incident incident, Actor actor, String eventType, String field, String oldValue, String newValue,
                       String message, Instant at) {
        IncidentHistory entry = new IncidentHistory();
        entry.setIncident(incident);
        entry.setActorId(actor.id());
        entry.setActorName(actor.name());
        entry.setEventType(eventType);
        entry.setField(field);
        entry.setOldValue(truncate(oldValue));
        entry.setNewValue(truncate(newValue));
        entry.setMessage(message == null ? null : message.length() > 1000 ? message.substring(0, 999) + "…" : message);
        entry.setCreatedAt(at);
        repository.save(entry);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 499) + "…";
    }
}

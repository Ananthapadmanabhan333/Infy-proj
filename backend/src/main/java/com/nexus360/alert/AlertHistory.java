package com.nexus360.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.security.Actor;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Append-only alert history ({@code alert_events}): CREATED, REPEATED, REOPENED, AUTO_RESOLVED, ACKNOWLEDGED, RESOLVED, SUPPRESSED,
 * UNSUPPRESSED, ROUTED, ASSIGNED, ESCALATED, CONVERTED, LINKED_INCIDENT, COMMENT.
 */
@Component
@RequiredArgsConstructor
public class AlertHistory {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public void record(Long alertId, String type, Actor actor, String note, Map<String, ?> details) {
        String detailJson = null;
        if (details != null && !details.isEmpty()) {
            try {
                detailJson = json.writeValueAsString(details);
            } catch (JsonProcessingException e) {
                detailJson = null;
            }
        }
        jdbc.update("insert into alert_events (alert_id, event_type, actor_id, actor_name, note, details, created_at) values (?, ?, ?, ?, ?, ?::jsonb, ?)",
                alertId, type, actor == null || actor.isSystem() ? null : actor.id(), actor == null ? "SA Automation" : actor.name(),
                note == null ? null : note.length() > 1000 ? note.substring(0, 1000) : note, detailJson, Timestamp.from(clock.instant()));
    }
}

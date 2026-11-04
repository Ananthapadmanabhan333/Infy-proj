package com.nexus360.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Each (SLA clock, escalation level) escalates and notifies exactly once, however often the evaluator runs. */
class SlaEscalationIdempotencyIT extends IntegrationTest {

    @Autowired
    SlaEngine engine;

    @Autowired
    JdbcTemplate jdbc;

    private List<Integer> slaLevels(long slaId) {
        return jdbc.queryForList("select level from incident_escalations where incident_sla_id = ? and source = 'SLA' order by level",
                Integer.class, slaId);
    }

    private long notifications(long userId, String type, long incidentId) {
        return jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = ? and link = ?", Long.class,
                userId, type, "/incidents/" + incidentId);
    }

    private long events(long slaId, String type) {
        return jdbc.queryForObject("select count(*) from sla_events where incident_sla_id = ? and event_type = ?", Long.class, slaId, type);
    }

    @Test
    void reRunningTheEvaluatorNeverReEscalatesOrReNotifies() throws Exception {
        JsonNode created = createIncident("rohan.kulkarni", Map.of("title", unique("Treasury cash position report blank"),
                "description", "Report shows no rows", "impact", "HIGH", "urgency", "HIGH"));
        long id = created.at("/incident/id").asLong();
        long assignee = created.at("/incident/assigneeId").asLong();
        assertThat(created.at("/incident/priority").asText()).isEqualTo("CRITICAL"); // P1: respond 15 min, resolve 240 min
        long slaId = jdbc.queryForObject("select id from incident_slas where incident_id = ?", Long.class, id);

        testClock().advance(Duration.ofMinutes(250)); // response missed and resolution breached
        engine.evaluate();
        assertThat(slaLevels(slaId)).containsExactly(1, 2);
        long breachedNotes = notifications(assignee, "SLA_BREACHED", id);
        assertThat(breachedNotes).isEqualTo(1);
        assertThat(events(slaId, "BREACHED")).isEqualTo(2); // response + resolution
        Thread.sleep(1500); // let the SLA_BREACHED workflows triggered by the first run settle

        engine.evaluate();
        engine.evaluate();
        assertThat(slaLevels(slaId)).containsExactly(1, 2);
        assertThat(notifications(assignee, "SLA_BREACHED", id)).isEqualTo(breachedNotes);
        assertThat(events(slaId, "BREACHED")).isEqualTo(2);

        // Even if the stored health is reset (e.g. by a policy recalculation) the breach is not alerted again.
        jdbc.update("update incident_slas set health = 'ON_TRACK' where id = ?", slaId);
        engine.evaluate();
        assertThat(jdbc.queryForObject("select health from incident_slas where id = ?", String.class, slaId)).isEqualTo("BREACHED");
        assertThat(notifications(assignee, "SLA_BREACHED", id)).isEqualTo(breachedNotes);
        assertThat(events(slaId, "BREACHED")).isEqualTo(2);

        testClock().advance(Duration.ofMinutes(130)); // 380 of 240 minutes: severe overrun -> level 3, once
        engine.evaluate();
        Thread.sleep(500);
        engine.evaluate();
        assertThat(slaLevels(slaId)).containsExactly(1, 2, 3);
        assertThat(events(slaId, "ESCALATED")).isEqualTo(2); // level 2 and level 3

        // The database guard rejects a second escalation of the same level for the same clock.
        assertThatThrownBy(() -> jdbc.update("insert into incident_escalations (incident_id, incident_sla_id, level, source, reason, actor_name) "
                + "values (?, ?, 2, 'SLA', 'duplicate', 'test')", id, slaId)).isInstanceOf(DataIntegrityViolationException.class);

        JsonNode detail = body(mvc.perform(as("sunita.joshi", get("/api/incidents/" + id))).andExpect(status().isOk()).andReturn());
        List<String> sources = detail.get("escalations").findValuesAsText("source");
        assertThat(sources.stream().filter("SLA"::equals).count()).isEqualTo(3);
        assertThat(detail.get("escalationLevel").asInt()).isEqualTo(3);
    }
}

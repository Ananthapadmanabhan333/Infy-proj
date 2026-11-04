package com.nexus360.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus360.incident.IncidentEscalation;
import com.nexus360.incident.IncidentEscalationRepository;
import com.nexus360.support.RepositoryTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Preference lookups used when fanning out notifications, and the escalation history queries. */
class NotificationPreferenceRepositoryTest extends RepositoryTest {

    @Autowired
    NotificationPreferenceRepository preferences;

    @Autowired
    IncidentEscalationRepository escalations;

    @Autowired
    JdbcTemplate jdbc;

    private long user() {
        String email = "pref-" + UUID.randomUUID() + "@example.test";
        jdbc.update("insert into users (email, full_name, password_hash, role) values (?, 'Pref User', 'x', 'SUPPORT_AGENT')", email);
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private void save(long user, String type, boolean inApp) {
        NotificationPreference p = new NotificationPreference();
        p.setId(new NotificationPreference.Key(user, type));
        p.setInApp(inApp);
        p.setUpdatedAt(Instant.now());
        preferences.saveAndFlush(p);
    }

    @Test
    void disabledUsersAreFoundPerCategory() {
        long off = user();
        long on = user();
        long untouched = user();
        save(off, "SLA_AT_RISK", false);
        save(off, "INCIDENT_COMMENT", true);
        save(on, "SLA_AT_RISK", true);

        assertThat(preferences.findDisabledUsers("SLA_AT_RISK", List.of(off, on, untouched))).containsExactly(off);
        assertThat(preferences.findDisabledUsers("INCIDENT_COMMENT", List.of(off, on, untouched))).isEmpty();
        assertThat(preferences.findDisabledUsers("SLA_AT_RISK", List.of(on, untouched))).isEmpty();
        assertThat(preferences.findByUserId(off)).extracting(p -> p.getId().getType())
                .containsExactlyInAnyOrder("SLA_AT_RISK", "INCIDENT_COMMENT");

        // Re-saving the same (user, type) updates the row instead of adding one.
        save(off, "SLA_AT_RISK", true);
        assertThat(preferences.findByUserId(off)).hasSize(2);
        assertThat(preferences.findDisabledUsers("SLA_AT_RISK", List.of(off))).isEmpty();
    }

    @Test
    void escalationsAreListedInOrderAndCountedPerClock() {
        jdbc.update("insert into incidents (number, title, category, impact, urgency, priority, status, updated_at) "
                + "values (?, 'Repository escalation test', 'OTHER', 'LOW', 'LOW', 'LOW', 'NEW', now())", "INC-R" + UUID.randomUUID().toString().substring(0, 8));
        long incident = jdbc.queryForObject("select max(id) from incidents where title = 'Repository escalation test'", Long.class);
        long policy = jdbc.queryForObject("select min(id) from sla_policies", Long.class);
        jdbc.update("insert into incident_slas (incident_id, policy_id, policy_name, priority, business_hours_only, response_target_minutes, "
                + "resolution_target_minutes, at_risk_percent, started_at, running_since, response_due_at, resolution_due_at) "
                + "values (?, ?, 'Test', 'LOW', false, 60, 480, 75, now(), now(), now(), now())", incident, policy);
        long sla = jdbc.queryForObject("select id from incident_slas where incident_id = ?", Long.class, incident);
        jdbc.update("insert into incident_escalations (incident_id, incident_sla_id, level, source, reason, actor_name) values "
                + "(?, ?, 1, 'SLA', 'Response missed', 'SLA engine'), (?, ?, 2, 'SLA', 'Resolution breached', 'SLA engine'), "
                + "(?, null, 2, 'MANUAL', 'Customer impact', 'Riya Varma')", incident, sla, incident, sla, incident);

        List<IncidentEscalation> history = escalations.findByIncidentIdOrderByCreatedAtAscIdAsc(incident);
        assertThat(history).extracting(IncidentEscalation::getLevel).containsExactly(1, 2, 2);
        assertThat(escalations.countByIncidentSlaId(sla)).isEqualTo(2);
        assertThat(escalations.existsByIncidentSlaIdAndLevel(sla, 2)).isTrue();
        assertThat(escalations.existsByIncidentSlaIdAndLevel(sla, 3)).isFalse();
    }
}

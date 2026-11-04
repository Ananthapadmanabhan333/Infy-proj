package com.nexus360.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E-mail without SMTP (the default test context has no MAIL_HOST): every message is recorded as SKIPPED, nothing claims
 * delivery, one-time links are never stored, and the e-mail notification channel follows the user's preferences.
 */
class EmailOutboxIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EmailService email;

    @Autowired
    EmailOutboxProcessor processor;

    @Autowired
    EmailOutboxRepository outbox;

    private long rows(String to, String category) {
        return jdbc.queryForObject("select count(*) from email_outbox where to_address = ? and category = ?", Long.class, to, category);
    }

    @Test
    void withoutSmtpMessagesAreSkippedAndSensitiveBodiesAreNotStored() {
        String to = "skip-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
        EmailService.Queued plain = email.queue(EmailService.Request.of(EmailService.TEST, to, null, "Plain",
                EmailTemplates.Content.of("Hello", "Regular body", null, null, null), false));
        EmailService.Queued secret = email.queue(EmailService.Request.of(EmailService.TEST, to, null, "Secret",
                EmailTemplates.Content.of("Hello", "token=SECRET-TOKEN-VALUE", "Use", "https://sa.example.test/x?token=SECRET-TOKEN-VALUE", null), true));
        assertThat(plain.status()).isEqualTo(EmailMessage.Status.SKIPPED);
        assertThat(plain.willBeSent()).isFalse();
        EmailMessage p = outbox.findById(plain.id()).orElseThrow();
        assertThat(p.getAttempts()).isZero();
        assertThat(p.getSentAt()).isNull();
        assertThat(p.getLastError()).contains("not configured");
        assertThat(p.getTextBody()).contains("Regular body");
        EmailMessage s = outbox.findById(secret.id()).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(EmailMessage.Status.SKIPPED);
        assertThat(s.getHtmlBody() + s.getTextBody()).doesNotContain("SECRET-TOKEN-VALUE").contains("not stored");
        assertThat(email.queue(EmailService.Request.of(EmailService.TEST, "not-an-address", null, "x",
                EmailTemplates.Content.of("x", "x", null, null, null), false)).id()).isNull();
    }

    @Test
    void pendingMessagesBecomeSkippedWhenSmtpIsNotConfigured() {
        String to = "pending-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
        jdbc.update("insert into email_outbox (category, to_address, subject, html_body, text_body, status, next_attempt_at) "
                + "values ('TEST', ?, 's', 'h', 't', 'PENDING', now())", to);
        EmailOutboxProcessor.DrainResult result = processor.drain();
        assertThat(result.sent()).isZero();
        assertThat(result.skipped()).isPositive();
        assertThat(jdbc.queryForObject("select status from email_outbox where to_address = ?", String.class, to)).isEqualTo("SKIPPED");
    }

    @Test
    void emailChannelFollowsNotificationPreferences() throws Exception {
        JsonNode prefs = getJson("vishal.kumar", "/api/notifications/preferences");
        JsonNode assigned = prefs.findParents("type").stream().filter(n -> "INCIDENT_ASSIGNED".equals(n.get("type").asText())).findFirst().orElseThrow();
        assertThat(assigned.get("email").asBoolean()).isTrue();
        assertThat(assigned.get("emailDefault").asBoolean()).isTrue();
        JsonNode comment = prefs.findParents("type").stream().filter(n -> "INCIDENT_COMMENT".equals(n.get("type").asText())).findFirst().orElseThrow();
        assertThat(comment.get("email").asBoolean()).isFalse();

        long euc = jdbc.queryForObject("select id from business_services where code = 'EUC'", Long.class);
        long vishal = jdbc.queryForObject("select id from users where email = ?", Long.class, email("vishal.kumar"));
        long before = rows(email("vishal.kumar"), EmailService.NOTIFICATION);
        long id = createIncident("tanvi.desai", Map.of("title", unique("Conference room touch panel freezes at login"),
                "description", "The touch panel freezes on the sign-in screen", "serviceId", euc)).at("/incident/id").asLong();
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + id + "/assign")), Map.of("assigneeId", vishal))).andExpect(status().isOk());
        assertThat(rows(email("vishal.kumar"), EmailService.NOTIFICATION)).isGreaterThan(before);
        assertThat(jdbc.queryForObject("select status from email_outbox where to_address = ? and category = 'NOTIFICATION' order by id desc limit 1",
                String.class, email("vishal.kumar"))).isEqualTo("SKIPPED");

        try {
            mvc.perform(jsonBody(as("vishal.kumar", put("/api/notifications/preferences")), Map.of("INCIDENT_ASSIGNED", Map.of("email", false),
                    "INCIDENT_COMMENT", Map.of("inApp", false, "email", true)))).andExpect(status().isOk());
            JsonNode updated = getJson("vishal.kumar", "/api/notifications/preferences");
            JsonNode a = updated.findParents("type").stream().filter(n -> "INCIDENT_ASSIGNED".equals(n.get("type").asText())).findFirst().orElseThrow();
            assertThat(a.get("email").asBoolean()).isFalse();
            assertThat(a.get("inApp").asBoolean()).isTrue(); // untouched
            JsonNode c = updated.findParents("type").stream().filter(n -> "INCIDENT_COMMENT".equals(n.get("type").asText())).findFirst().orElseThrow();
            assertThat(c.get("email").asBoolean()).isTrue();
            assertThat(c.get("inApp").asBoolean()).isFalse();

            long second = createIncident("tanvi.desai", Map.of("title", unique("Scanner feeder jams on duplex documents"),
                    "description", "Duplex scanning jams the feeder", "serviceId", euc)).at("/incident/id").asLong();
            long afterOptOut = rows(email("vishal.kumar"), EmailService.NOTIFICATION);
            mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + second + "/assign")), Map.of("assigneeId", vishal))).andExpect(status().isOk());
            assertThat(rows(email("vishal.kumar"), EmailService.NOTIFICATION)).isEqualTo(afterOptOut);
            assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'INCIDENT_ASSIGNED' and link = ?",
                    Long.class, vishal, "/incidents/" + second)).isEqualTo(1); // in-app still delivered
        } finally {
            mvc.perform(jsonBody(as("vishal.kumar", put("/api/notifications/preferences")), Map.of("INCIDENT_ASSIGNED", Map.of("email", true),
                    "INCIDENT_COMMENT", Map.of("inApp", true, "email", false)))).andExpect(status().isOk());
        }
        mvc.perform(jsonBody(as("vishal.kumar", put("/api/notifications/preferences")), Map.of("INCIDENT_ASSIGNED", "yes")))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("vishal.kumar", put("/api/notifications/preferences")), Map.of("INCIDENT_ASSIGNED", Map.of("sms", true))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invitationLinksAreNotEmailedWithoutSmtpAndAdministratorsSeeTheStatus() throws Exception {
        String to = "invitee-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
        long dept = jdbc.queryForObject("select id from departments where code = 'IT-SD'", Long.class);
        JsonNode link = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/admin/invitations")), Map.of("email", to, "role", "EMPLOYEE",
                "departmentId", dept))).andExpect(status().isOk()).andReturn());
        assertThat(link.get("emailQueued").asBoolean()).isFalse();
        String token = link.get("link").asText().substring("/register?invite=".length());
        List<Map<String, Object>> stored = jdbc.queryForList("select status, html_body, text_body from email_outbox where to_address = ?", to);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).get("status")).isEqualTo("SKIPPED");
        assertThat(stored.get(0).get("html_body") + " " + stored.get(0).get("text_body")).doesNotContain(token);

        mvc.perform(as("rajesh.iyer", get("/api/admin/email/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false)).andExpect(jsonPath("$.fromAddress").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(as("rajesh.iyer", get("/api/admin/email/outbox?status=SKIPPED&size=5"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("SKIPPED"));
        mvc.perform(as("priya.raman", get("/api/admin/email/outbox"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("admin", post("/api/admin/email/test")), Map.of("to", "ops@example.org"))).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/admin/email/test")), Map.of("to", "ops@example.org"))).andExpect(status().isForbidden());
    }
}

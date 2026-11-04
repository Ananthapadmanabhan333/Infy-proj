package com.nexus360.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.nexus360.security.Hashing;
import com.nexus360.statuspage.StatusSubscriptionService;
import com.nexus360.support.IntegrationTest;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real SMTP delivery against an in-process GreenMail server (its own application context with MAIL_HOST set): messages
 * are actually sent, failures are retried with backoff, invitation/reset links and status-page confirmations reach the
 * recipient with absolute APP_PUBLIC_URL links, and one-time links are removed from the outbox once delivered.
 */
class EmailDeliveryIT extends IntegrationTest {

    private static final String PUBLIC_URL = "https://sa.example.test";
    private static final int PORT = freePort();
    private static GreenMail smtp = start();

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("app.mail.host", () -> "127.0.0.1");
        registry.add("app.mail.port", () -> String.valueOf(PORT));
        registry.add("app.mail.from", () -> "no-reply@sa.example.test");
        registry.add("app.mail.starttls", () -> "false");
        registry.add("app.mail.max-attempts", () -> "3");
        registry.add("app.mail.timeout-ms", () -> "3000");
        registry.add("app.public-url", () -> PUBLIC_URL);
    }

    @AfterAll
    static void stopSmtp() {
        smtp.stop();
    }

    @Autowired
    EmailService email;

    @Autowired
    EmailOutboxProcessor processor;

    @Autowired
    EmailOutboxRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void messagesAreDeliveredOverSmtpWithEscapedContent() throws Exception {
        String to = address("deliver");
        EmailService.Queued queued = email.queue(EmailService.Request.of(EmailService.TEST, to, null, "Delivery check",
                new EmailTemplates.Content("Incident <b>INC-1</b>", List.of("Reported by Tom & <i>Jerry</i>"), List.of(),
                        "Open", email.link("/incidents/1"), null), false));
        assertThat(queued.status()).isEqualTo(EmailMessage.Status.PENDING);
        assertThat(processor.drain().sent()).isPositive();

        MimeMessage message = received(to);
        assertThat(message.getSubject()).isEqualTo("Delivery check");
        assertThat(message.getFrom()[0].toString()).contains("no-reply@sa.example.test");
        String html = part(message, "text/html");
        assertThat(html).contains("Incident &lt;b&gt;INC-1&lt;/b&gt;", "Tom &amp; &lt;i&gt;Jerry&lt;/i&gt;", "href=\"" + PUBLIC_URL + "/incidents/1\"");
        assertThat(html).doesNotContain("<i>Jerry</i>");
        assertThat(part(message, "text/plain")).contains("Open: " + PUBLIC_URL + "/incidents/1");
        EmailMessage stored = outbox.findById(queued.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(EmailMessage.Status.SENT);
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getSentAt()).isNotNull();
        mvc.perform(as("rajesh.iyer", get("/api/admin/email/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.fromAddress").value("no-reply@sa.example.test"));
        assertThat(getJson("rajesh.iyer", "/api/admin/system-health").at("/email/status").asText()).isEqualTo("CONFIGURED");
    }

    @Test
    void failedDeliveriesAreRetriedWithBackoffAndEventuallyMarkedFailed() throws Exception {
        String to = address("retry");
        String doomed = address("doomed");
        smtp.stop();
        EmailService.Queued queued;
        try {
            queued = email.queue(EmailService.Request.of(EmailService.TEST, to, null, "Retry check",
                    EmailTemplates.Content.of("Retry", "Body", null, null, null), false));
            processor.drain();
            EmailMessage first = outbox.findById(queued.id()).orElseThrow();
            assertThat(first.getStatus()).isEqualTo(EmailMessage.Status.PENDING);
            assertThat(first.getAttempts()).isEqualTo(1);
            assertThat(first.getLastError()).isNotBlank();
            assertThat(first.getNextAttemptAt()).isAfter(clock.instant());
            processor.drain(); // not due yet: no new attempt
            assertThat(outbox.findById(queued.id()).orElseThrow().getAttempts()).isEqualTo(1);
        } finally {
            smtp = start();
        }
        // The SMTP server is back: once the backoff has passed the same message is delivered on its second attempt.
        testClock().advance(Duration.ofMinutes(1));
        processor.drain();
        EmailMessage delivered = outbox.findById(queued.id()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(EmailMessage.Status.SENT);
        assertThat(delivered.getAttempts()).isEqualTo(2);
        assertThat(received(to).getSubject()).isEqualTo("Retry check");

        // A message that keeps failing is given up after MAIL_MAX_ATTEMPTS (3) and its one-time link is not kept.
        smtp.stop();
        try {
            EmailService.Queued lost = email.queue(EmailService.Request.of(EmailService.TEST, doomed, null, "Doomed",
                    EmailTemplates.Content.of("Doomed", "token=ONE-TIME-SECRET", null, null, null), true));
            for (int i = 0; i < 3; i++) {
                testClock().advance(Duration.ofHours(1));
                processor.drain();
            }
            EmailMessage failed = outbox.findById(lost.id()).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(EmailMessage.Status.FAILED);
            assertThat(failed.getAttempts()).isEqualTo(3);
            assertThat(failed.getNextAttemptAt()).isNull();
            assertThat(failed.getTextBody() + failed.getHtmlBody()).doesNotContain("ONE-TIME-SECRET");
        } finally {
            smtp = start();
        }
        assertThat(recipients(doomed)).isEmpty();
    }

    @Test
    void invitationAndResetLinksAreEmailedToTheRecipient() throws Exception {
        String invitee = address("invitee");
        long dept = jdbc.queryForObject("select id from departments where code = 'IT-SD'", Long.class);
        JsonNode link = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/admin/invitations")), Map.of("email", invitee, "fullName",
                "Ira <Invitee>", "role", "EMPLOYEE", "departmentId", dept))).andExpect(status().isOk()).andReturn());
        assertThat(link.get("emailQueued").asBoolean()).isTrue();
        String token = link.get("link").asText().substring("/register?invite=".length());
        processor.drain();
        MimeMessage invitation = received(invitee);
        assertThat(invitation.getSubject()).startsWith("Your invitation to SA");
        String text = part(invitation, "text/plain");
        assertThat(text).contains(PUBLIC_URL + "/register?invite=" + token);
        assertThat(part(invitation, "text/html")).contains("Hello Ira &lt;Invitee&gt;,").doesNotContain("Ira <Invitee>");
        mvc.perform(get("/api/auth/invitations/" + token)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(invitee));
        assertThat(storedBodies(invitee)).doesNotContain(token); // redacted once delivered

        // Password reset: the request is generic; the approved link is e-mailed to the account's address.
        String local = "reset." + UUID.randomUUID().toString().substring(0, 8);
        String account = local + "@demo.nexus360.io";
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/users")), Map.of("email", account, "fullName", "Reset Tester", "role", "SUPPORT_AGENT",
                "departmentId", dept, "password", "Release3-Pass-2026"))).andExpect(status().isCreated());
        mvc.perform(jsonBody(post("/api/auth/forgot-password"), Map.of("email", account))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sent to this e-mail address")));
        long requestId = jdbc.queryForObject("select id from account_requests where email = ? and type = 'PASSWORD_RESET' and status = 'PENDING'",
                Long.class, account);
        JsonNode reset = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/admin/account-requests/" + requestId + "/approve")), Map.of()))
                .andExpect(status().isOk()).andReturn());
        assertThat(reset.get("emailQueued").asBoolean()).isTrue();
        String resetToken = reset.get("link").asText().substring("/reset-password?token=".length());
        processor.drain();
        assertThat(part(received(account), "text/plain")).contains(PUBLIC_URL + "/reset-password?token=" + resetToken);
        mvc.perform(get("/api/auth/reset-password/" + resetToken)).andExpect(status().isOk());
        assertThat(storedBodies(account)).doesNotContain(resetToken);
    }

    @Test
    void notificationEmailsAreSentForAssignments() throws Exception {
        long db = jdbc.queryForObject("select id from business_services where code = 'DATABASE'", Long.class);
        long id = createIncident("divya.pillai", Map.of("title", unique("Treasury ledger export job deadlocks nightly"),
                "description", "Ledger export job deadlocks at midnight", "serviceId", db)).at("/incident/id").asLong();
        String number = jdbc.queryForObject("select number from incidents where id = ?", String.class, id);
        processor.drain();
        MimeMessage message = received(email("farhan.ali"), "Assigned to you: " + number);
        assertThat(part(message, "text/plain")).contains(PUBLIC_URL + "/incidents/" + id);
    }

    @Test
    void statusPageSubscriptionsAreDoubleOptInWithHashedTokens() throws Exception {
        JsonNode original = getJson("admin", "/api/status-page").get("settings");
        mvc.perform(jsonBody(as("admin", put("/api/status-page/settings")), Map.of("title", original.get("title").asText(),
                "publicEnabled", true))).andExpect(status().isOk());
        try {
            assertThat(body(mvc.perform(get("/api/public/status")).andExpect(status().isOk()).andReturn()).get("subscriptionsAvailable").asBoolean())
                    .isTrue();
            String subscriber = address("subscriber");
            mvc.perform(jsonBody(post("/api/public/status/subscribe"), Map.of("email", subscriber))).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.message").value(StatusSubscriptionService.SUBSCRIBE_MESSAGE));
            Map<String, Object> row = jdbc.queryForMap("select * from status_subscribers where email = ?", subscriber);
            assertThat(row.get("confirmed_at")).isNull();
            assertThat((String) row.get("confirm_token_hash")).matches("[0-9a-f]{64}");
            assertThat((String) row.get("unsubscribe_token_hash")).matches("[0-9a-f]{64}");

            processor.drain();
            String confirmToken = token(part(received(subscriber), "text/plain"), "confirm");
            assertThat(row.get("confirm_token_hash")).isEqualTo(Hashing.sha256Hex(confirmToken));
            assertThat(storedBodies(subscriber)).doesNotContain(confirmToken);

            mvc.perform(jsonBody(post("/api/public/status/confirm"), Map.of("token", "not-a-real-token-1234567890"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(StatusSubscriptionService.CONFIRM_MESSAGE));
            assertThat(jdbc.queryForObject("select confirmed_at from status_subscribers where email = ?", Object.class, subscriber)).isNull();
            mvc.perform(jsonBody(post("/api/public/status/confirm"), Map.of("token", confirmToken))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(StatusSubscriptionService.CONFIRM_MESSAGE));
            Map<String, Object> confirmed = jdbc.queryForMap("select * from status_subscribers where email = ?", subscriber);
            assertThat(confirmed.get("confirmed_at")).isNotNull();
            assertThat(confirmed.get("confirm_token_hash")).isNull();

            // Subscribing again changes nothing and still answers generically.
            long before = outbox.findByToAddressIgnoreCaseOrderByIdAsc(subscriber).size();
            mvc.perform(jsonBody(post("/api/public/status/subscribe"), Map.of("email", subscriber))).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.message").value(StatusSubscriptionService.SUBSCRIBE_MESSAGE));
            assertThat(outbox.findByToAddressIgnoreCaseOrderByIdAsc(subscriber)).hasSize((int) before);

            // A public update reaches confirmed subscribers with a personal unsubscribe link.
            mvc.perform(jsonBody(as("sunita.joshi", post("/api/status-page/updates")), Map.of("title", "Delivery test update",
                    "status", "MONITORING", "message", "Monitoring <the> fix", "publicVisible", true))).andExpect(status().isCreated());
            processor.drain();
            MimeMessage update = received(subscriber, "Monitoring: Delivery test update");
            assertThat(part(update, "text/html")).contains("Monitoring &lt;the&gt; fix");
            String unsubscribeToken = token(part(update, "text/plain"), "unsubscribe");
            assertThat(confirmed.get("unsubscribe_token_hash")).isEqualTo(Hashing.sha256Hex(unsubscribeToken));

            mvc.perform(jsonBody(post("/api/public/status/unsubscribe"), Map.of("token", unsubscribeToken))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(StatusSubscriptionService.UNSUBSCRIBE_MESSAGE));
            assertThat(jdbc.queryForObject("select count(*) from status_subscribers where email = ?", Long.class, subscriber)).isZero();
            mvc.perform(jsonBody(post("/api/public/status/unsubscribe"), Map.of("token", unsubscribeToken))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(StatusSubscriptionService.UNSUBSCRIBE_MESSAGE));
            assertThat(jdbc.queryForObject("select count(*) from audit_logs where action in ('STATUS_SUBSCRIBE_REQUESTED', "
                    + "'STATUS_SUBSCRIPTION_CONFIRMED', 'STATUS_UNSUBSCRIBED') and actor_email = ?", Long.class, subscriber)).isGreaterThanOrEqualTo(4);
        } finally {
            mvc.perform(jsonBody(as("admin", put("/api/status-page/settings")), Map.of("title", original.get("title").asText(),
                    "description", original.get("description").asText(), "publicEnabled", original.get("publicEnabled").asBoolean())))
                    .andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String address(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
    }

    private String storedBodies(String to) {
        StringBuilder sb = new StringBuilder();
        outbox.findByToAddressIgnoreCaseOrderByIdAsc(to).forEach(m -> sb.append(m.getHtmlBody()).append(m.getTextBody()));
        return sb.toString();
    }

    private static MimeMessage received(String to) throws Exception {
        return received(to, null);
    }

    private static MimeMessage received(String to, String subject) throws Exception {
        for (int i = 0; i < 50; i++) {
            for (MimeMessage m : smtp.getReceivedMessages()) {
                if (Arrays.stream(m.getAllRecipients()).map(Address::toString).anyMatch(to::equalsIgnoreCase)
                        && (subject == null || subject.equals(m.getSubject()))) {
                    return m;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No e-mail to " + to + (subject == null ? "" : " with subject " + subject));
    }

    private static List<MimeMessage> recipients(String to) throws Exception {
        List<MimeMessage> out = new ArrayList<>();
        for (MimeMessage m : smtp.getReceivedMessages()) {
            if (Arrays.stream(m.getAllRecipients()).map(Address::toString).anyMatch(to::equalsIgnoreCase)) {
                out.add(m);
            }
        }
        return out;
    }

    /** The decoded body of the first part with the given MIME type. */
    private static String part(Part p, String mimeType) throws Exception {
        if (p.isMimeType(mimeType)) {
            return p.getContent().toString();
        }
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart child = mp.getBodyPart(i);
                String found = part(child, mimeType);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String token(String text, String parameter) {
        Matcher m = Pattern.compile("[?&]" + parameter + "=([A-Za-z0-9_-]+)").matcher(text);
        assertThat(m.find()).as(parameter + " link in " + text).isTrue();
        return m.group(1);
    }

    private static GreenMail start() {
        GreenMail mail = new GreenMail(new ServerSetup(PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
                .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());
        mail.start();
        return mail;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

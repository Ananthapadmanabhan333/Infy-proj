package com.nexus360.email;

import com.nexus360.admin.SettingsService;
import com.nexus360.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues e-mails in the outbox (same transaction as the business change). Delivery happens asynchronously in
 * {@link EmailOutboxProcessor}. When SMTP is not configured the message is stored as SKIPPED right away: it is never
 * reported as sent, and sensitive bodies (one-time links) are not stored at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    /** Outbox categories (free text in the database; these are the ones the platform uses). */
    public static final String NOTIFICATION = "NOTIFICATION";
    public static final String INVITATION = "INVITATION";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String STATUS_SUBSCRIPTION = "STATUS_SUBSCRIPTION";
    public static final String STATUS_UPDATE = "STATUS_UPDATE";
    public static final String REPORT = "REPORT";
    public static final String TEST = "TEST";

    /** Attachments above this size are not e-mailed (the run stays downloadable in the app). */
    public static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;

    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}$");

    private final EmailOutboxRepository outbox;
    private final MailTransport transport;
    private final AppProperties properties;
    private final SettingsService settings;
    private final Clock clock;

    /** One message to queue. {@code sensitive}: contains a one-time link (body redacted once final). */
    public record Request(String category, String to, Long userId, String subject, EmailTemplates.Content content, boolean sensitive,
                          String attachmentName, String attachmentContentType, byte[] attachment) {

        public static Request of(String category, String to, Long userId, String subject, EmailTemplates.Content content, boolean sensitive) {
            return new Request(category, to, userId, subject, content, sensitive, null, null, null);
        }
    }

    public record Queued(Long id, EmailMessage.Status status) {

        public boolean willBeSent() {
            return status == EmailMessage.Status.PENDING;
        }
    }

    public boolean configured() {
        return transport.configured();
    }

    /** Absolute link into the web app for an app-relative path such as {@code /incidents/12}; null when no base URL is known. */
    public String link(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String base = properties.publicBaseUrl();
        if (base.isEmpty()) {
            return null;
        }
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    @Transactional
    public Queued queue(Request r) {
        String to = r.to() == null ? "" : r.to().trim();
        if (!ADDRESS.matcher(to).matches()) {
            log.warn("Not queuing {} e-mail: invalid recipient address", r.category());
            return new Queued(null, EmailMessage.Status.SKIPPED);
        }
        String subject = truncate(r.subject() == null ? "SA notification" : r.subject().replaceAll("[\\r\\n]+", " ").trim(), 300);
        EmailTemplates.Rendered rendered = EmailTemplates.render(subject, r.content(), settings.getString(SettingsService.ORG_NAME, "SA"));
        Instant now = clock.instant();
        EmailMessage m = new EmailMessage();
        m.setCategory(r.category());
        m.setToAddress(to.toLowerCase(Locale.ROOT));
        m.setUserId(r.userId());
        m.setSubject(subject);
        m.setSensitive(r.sensitive());
        m.setHtmlBody(rendered.html());
        m.setTextBody(rendered.text());
        m.setCreatedAt(now);
        if (r.attachment() != null && r.attachment().length <= MAX_ATTACHMENT_BYTES) {
            m.setAttachmentName(r.attachmentName());
            m.setAttachmentContentType(r.attachmentContentType());
            m.setAttachmentData(r.attachment());
        }
        if (transport.configured()) {
            m.setStatus(EmailMessage.Status.PENDING);
            m.setNextAttemptAt(now);
        } else {
            m.setLastError("SMTP is not configured (MAIL_HOST)");
            m.finish(EmailMessage.Status.SKIPPED, now);
        }
        outbox.save(m);
        return new Queued(m.getId(), m.getStatus());
    }

    /** Delay before retry number {@code attempt} (1-based): 30 s, 1, 2, 4, 8 … minutes, at most one hour. */
    public static Duration backoff(int attempt) {
        long seconds = 30L << Math.min(Math.max(attempt - 1, 0), 7);
        return Duration.ofSeconds(Math.min(seconds, 3600));
    }

    private static String truncate(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }
}

package com.nexus360.statuspage;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.RequestInfo;
import com.nexus360.config.AppProperties;
import com.nexus360.email.EmailService;
import com.nexus360.email.EmailTemplates;
import com.nexus360.security.Hashing;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Double opt-in e-mail subscriptions to the public status page. Every public answer is generic (it never reveals whether
 * an address is subscribed). Only SHA-256 hashes of tokens are stored: the confirmation token is random (32 bytes, valid
 * 48 hours); the unsubscribe token is an HMAC of the subscription with a server-side key, so it can be put into every
 * update e-mail without being stored. Without SMTP nothing is recorded, because a subscription could never be confirmed.
 */
@Service
@RequiredArgsConstructor
public class StatusSubscriptionService {

    public static final String SUBSCRIBE_MESSAGE = "Thank you. If this address can receive status updates, a confirmation e-mail "
            + "is on its way; the subscription starts once you open the link in it.";
    public static final String CONFIRM_MESSAGE = "If the link was valid, your subscription to status updates is now confirmed.";
    public static final String UNSUBSCRIBE_MESSAGE = "If the link was valid, you will no longer receive status updates.";

    static final Duration CONFIRM_VALIDITY = Duration.ofHours(48);
    private static final Pattern TOKEN_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{16,200}$");

    private final StatusSubscriberRepository subscribers;
    private final StatusPageSettingsRepository settings;
    private final EmailService email;
    private final AppProperties properties;
    private final AuditService audit;
    private final Clock clock;

    public boolean available() {
        return email.configured() && publicEnabled();
    }

    @Transactional
    public void subscribe(String rawEmail) {
        requirePublic();
        String address = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!email.configured()) {
            audit.recordAs(null, address, "STATUS_SUBSCRIBE_REQUESTED", "STATUS_SUBSCRIBER", null,
                    Map.of("recorded", false, "outcome", "MAIL_NOT_CONFIGURED"), AuditService.SUCCESS);
            return;
        }
        Instant now = clock.instant();
        StatusSubscriber s = subscribers.findByEmail(address).orElse(null);
        if (s != null && s.getConfirmedAt() != null) {
            audit.recordAs(null, address, "STATUS_SUBSCRIBE_REQUESTED", "STATUS_SUBSCRIBER", s.getId(),
                    Map.of("recorded", false, "outcome", "ALREADY_CONFIRMED"), AuditService.SUCCESS);
            return;
        }
        boolean created = s == null;
        if (created) {
            s = new StatusSubscriber();
            s.setEmail(address);
            s.setCreatedAt(now.truncatedTo(ChronoUnit.MILLIS));
            s.setUnsubscribeTokenHash(Hashing.sha256Hex(unsubscribeToken(address, s.getCreatedAt())));
        }
        String confirmToken = Hashing.randomToken(32);
        s.setConfirmTokenHash(Hashing.sha256Hex(confirmToken));
        s.setConfirmExpiresAt(now.plus(CONFIRM_VALIDITY));
        s.setRequestIp(RequestInfo.clientIp());
        subscribers.save(s);
        String title = pageTitle();
        email.queue(EmailService.Request.of(EmailService.STATUS_SUBSCRIPTION, address, null, "Confirm your subscription: " + title,
                new EmailTemplates.Content("Confirm your subscription",
                        List.of("Someone (hopefully you) asked to receive e-mail updates from “" + title + "” at this address.",
                                "Open the link below within 48 hours to confirm. Nothing is sent until you do."),
                        List.of(), "Confirm subscription", email.link("/status?confirm=" + confirmToken),
                        "If you did not ask for this, ignore this message: the request expires on its own."), true));
        audit.recordAs(null, address, "STATUS_SUBSCRIBE_REQUESTED", "STATUS_SUBSCRIBER", s.getId(),
                Map.of("recorded", true, "outcome", created ? "CREATED" : "CONFIRMATION_RESENT"), AuditService.SUCCESS);
    }

    @Transactional
    public void confirm(String rawToken) {
        requirePublic();
        if (!wellFormed(rawToken)) {
            return;
        }
        Instant now = clock.instant();
        subscribers.findByConfirmTokenHash(Hashing.sha256Hex(rawToken))
                .filter(s -> s.getConfirmExpiresAt() != null && s.getConfirmExpiresAt().isAfter(now))
                .ifPresent(s -> {
                    s.setConfirmedAt(now);
                    s.setConfirmTokenHash(null);
                    s.setConfirmExpiresAt(null);
                    audit.recordAs(null, s.getEmail(), "STATUS_SUBSCRIPTION_CONFIRMED", "STATUS_SUBSCRIBER", s.getId(), null, AuditService.SUCCESS);
                });
    }

    @Transactional
    public void unsubscribe(String rawToken) {
        requirePublic();
        if (!wellFormed(rawToken)) {
            return;
        }
        subscribers.findByUnsubscribeTokenHash(Hashing.sha256Hex(rawToken)).ifPresent(s -> {
            audit.recordAs(null, s.getEmail(), "STATUS_UNSUBSCRIBED", "STATUS_SUBSCRIBER", s.getId(), null, AuditService.SUCCESS);
            subscribers.delete(s);
        });
    }

    /** Queues a status e-mail to every confirmed subscriber, each with a personal unsubscribe link. Returns the number queued. */
    @Transactional
    public int notifySubscribers(String subject, String heading, List<String> paragraphs, List<EmailTemplates.Fact> facts) {
        if (!publicEnabled()) {
            return 0;
        }
        int queued = 0;
        String statusUrl = email.link("/status");
        for (StatusSubscriber s : subscribers.findByConfirmedAtIsNotNullOrderByIdAsc()) {
            String unsubscribe = email.link("/status?unsubscribe=" + unsubscribeToken(s.getEmail(), s.getCreatedAt()));
            List<String> body = new java.util.ArrayList<>(paragraphs);
            if (unsubscribe != null) {
                body.add("To stop these e-mails, unsubscribe here: " + unsubscribe);
            }
            email.queue(EmailService.Request.of(EmailService.STATUS_UPDATE, s.getEmail(), null, subject,
                    new EmailTemplates.Content(heading, body, facts, "View the status page", statusUrl,
                            "You receive this because you subscribed to updates from “" + pageTitle() + "”."), true));
            queued++;
        }
        return queued;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Deterministic unsubscribe token: HMAC-SHA256 over the address and the subscription time with a key derived from the
     * server secret. Only its SHA-256 hash is stored; a database dump alone cannot produce valid tokens.
     */
    String unsubscribeToken(String address, Instant createdAt) {
        try {
            byte[] key = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("status-unsubscribe|" + properties.security().jwtSecret()).getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal((address.toLowerCase(Locale.ROOT) + "|" + createdAt.toEpochMilli()).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private boolean publicEnabled() {
        return settings.findById(StatusPageSettings.ID).map(StatusPageSettings::isPublicEnabled).orElse(false);
    }

    private String pageTitle() {
        return settings.findById(StatusPageSettings.ID).map(StatusPageSettings::getTitle).orElse("Service status");
    }

    private void requirePublic() {
        if (!publicEnabled()) {
            throw notPublished();
        }
    }

    static ApiException notPublished() {
        return new ApiException(HttpStatus.NOT_FOUND, "The status page is not published");
    }

    private static boolean wellFormed(String token) {
        return token != null && TOKEN_FORMAT.matcher(token).matches();
    }
}

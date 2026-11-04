package com.nexus360.access;

import com.nexus360.admin.SettingsService;
import com.nexus360.email.EmailService;
import com.nexus360.email.EmailTemplates;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * E-mails invitation and password-reset links to their recipient when SMTP is configured (docs/RELEASE3_SPEC.md §4). The
 * administrator still sees the link once in the API response. Messages are marked sensitive: the stored body is replaced
 * by a placeholder once delivered (or immediately when SMTP is not configured), so the raw token does not stay in the
 * outbox.
 */
@Component
@RequiredArgsConstructor
class AccountLinkMailer {

    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final EmailService email;
    private final SettingsService settings;

    /**
     * Queues the message; {@code true} when it will actually be delivered. Without SMTP the outbox records it as SKIPPED
     * (with a placeholder body); without any absolute base URL (APP_PUBLIC_URL / FRONTEND_ORIGIN) nothing is queued.
     */
    boolean send(AccountToken token, String appRelativeLink) {
        String url = email.link(appRelativeLink);
        if (url == null) {
            return false;
        }
        String org = settings.getString(SettingsService.ORG_NAME, "SA");
        boolean invite = token.getPurpose() == AccountTokenPurpose.INVITE;
        String greeting = token.getFullName() == null ? "Hello," : "Hello " + token.getFullName() + ",";
        EmailTemplates.Content content;
        String subject;
        if (invite) {
            subject = "Your invitation to SA (" + org + ")";
            content = new EmailTemplates.Content("You have been invited to SA",
                    List.of(greeting, "An administrator of " + org + " created an account invitation for " + token.getEmail()
                            + ". Open the link below to choose your password and sign in.",
                            "The link can be used once and expires on " + EXPIRY.format(token.getExpiresAt()) + "."),
                    token.getRole() == null ? List.of() : List.of(new EmailTemplates.Fact("Role", token.getRole().label())),
                    "Accept invitation", url,
                    "If you did not expect this invitation, ignore this message; no account is created unless the link is used.");
        } else {
            subject = "Reset your SA password";
            content = new EmailTemplates.Content("Reset your password",
                    List.of(greeting, "An administrator issued a password reset link for your SA account (" + token.getEmail() + ").",
                            "The link can be used once and expires on " + EXPIRY.format(token.getExpiresAt())
                                    + ". Using it signs you out of all sessions."),
                    List.of(), "Choose a new password", url,
                    "If you did not ask for a password reset, ignore this message and tell your administrator.");
        }
        return email.queue(new EmailService.Request(invite ? EmailService.INVITATION : EmailService.PASSWORD_RESET, token.getEmail(),
                token.getUserId(), subject, content, true, null, null, null)).willBeSent();
    }
}

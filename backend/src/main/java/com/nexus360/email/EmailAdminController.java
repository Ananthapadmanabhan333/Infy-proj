package com.nexus360.email;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Outbox visibility for administrators. Bodies are never returned; nothing here exposes SMTP credentials. */
@Tag(name = "E-mail (administration)")
@RestController
@RequestMapping("/api/admin/email")
@RequiredArgsConstructor
public class EmailAdminController {

    public record EmailStatus(boolean configured, String fromAddress, long pending, long sentLast24h, long failedLast24h,
                              long skippedLast24h, Instant lastSentAt, Instant lastRunAt) {}

    public record OutboxItem(Long id, String category, String to, String subject, EmailMessage.Status status, int attempts,
                             Instant nextAttemptAt, String lastError, Instant sentAt, Instant createdAt, boolean sensitive,
                             String attachmentName) {

        static OutboxItem of(EmailMessage m) {
            return new OutboxItem(m.getId(), m.getCategory(), m.getToAddress(), m.getSubject(), m.getStatus(), m.getAttempts(),
                    m.getNextAttemptAt(), m.getLastError(), m.getSentAt(), m.getCreatedAt(), m.isSensitive(), m.getAttachmentName());
        }
    }

    public record TestEmailRequest(@NotBlank @Email @Size(max = 160) String to) {}

    private final EmailOutboxRepository outbox;
    private final MailTransport transport;
    private final EmailOutboxProcessor processor;
    private final EmailService email;
    private final AuditService audit;
    private final Clock clock;

    @PreAuthorize(Roles.ADMINS)
    @GetMapping("/status")
    @Transactional(readOnly = true)
    public EmailStatus status() {
        Instant since = clock.instant().minus(Duration.ofHours(24));
        return new EmailStatus(transport.configured(), transport.configured() ? transport.fromAddress() : null,
                outbox.countByStatus(EmailMessage.Status.PENDING),
                outbox.countByStatusAndUpdatedAtAfter(EmailMessage.Status.SENT, since),
                outbox.countByStatusAndUpdatedAtAfter(EmailMessage.Status.FAILED, since),
                outbox.countByStatusAndUpdatedAtAfter(EmailMessage.Status.SKIPPED, since),
                outbox.findFirstByStatusOrderBySentAtDesc(EmailMessage.Status.SENT).map(EmailMessage::getSentAt).orElse(null),
                processor.lastRunAt());
    }

    @PreAuthorize(Roles.ADMINS)
    @GetMapping("/outbox")
    @Transactional(readOnly = true)
    public PageResponse<OutboxItem> list(@RequestParam(required = false) EmailMessage.Status status,
                                         @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        var pageable = PageResponse.pageable(page, size, org.springframework.data.domain.Sort.unsorted());
        var result = status == null ? outbox.findAllByOrderByCreatedAtDesc(pageable) : outbox.findByStatusOrderByCreatedAtDesc(status, pageable);
        return PageResponse.of(result, OutboxItem::of);
    }

    @Operation(summary = "Queue a test e-mail (SYSTEM_ADMIN); 409 when SMTP is not configured")
    @PreAuthorize(Roles.SYSTEM_ADMIN)
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public Map<String, Object> test(@Valid @RequestBody TestEmailRequest request) {
        if (!email.configured()) {
            throw ApiException.conflict("E-mail delivery is not configured (MAIL_HOST is not set)");
        }
        EmailService.Queued queued = email.queue(EmailService.Request.of(EmailService.TEST, request.to(), null, "SA test e-mail",
                EmailTemplates.Content.of("E-mail delivery works", "This is a test message from SA. No action is needed.",
                        "Open SA", email.link("/dashboard"), "An administrator sent this test message from Settings."), false));
        audit.record("EMAIL_TEST_QUEUED", "EMAIL", queued.id(), Map.of("to", request.to()));
        return Map.of("id", queued.id(), "status", queued.status());
    }
}

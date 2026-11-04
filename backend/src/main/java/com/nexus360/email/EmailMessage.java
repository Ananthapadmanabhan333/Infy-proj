package com.nexus360.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One row of the e-mail outbox. Written in the business transaction (so only committed work produces mail) and delivered
 * by {@link EmailOutboxProcessor}. {@code sensitive} messages carry a one-time link: their bodies are replaced by a
 * placeholder as soon as the message reaches a final status (SENT, FAILED, SKIPPED).
 */
@Getter
@Setter
@Entity
@Table(name = "email_outbox")
public class EmailMessage {

    public enum Status { PENDING, SENT, FAILED, SKIPPED }

    /** Placeholder stored instead of the body of a sensitive message once it can no longer be delivered again. */
    public static final String REDACTED_BODY = "[Message body not stored: it contained a one-time link.]";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(name = "to_address", nullable = false, length = 320)
    private String toAddress;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 300)
    private String subject;

    @Column(name = "html_body", nullable = false, columnDefinition = "text")
    private String htmlBody;

    @Column(name = "text_body", nullable = false, columnDefinition = "text")
    private String textBody;

    @Column(nullable = false)
    private boolean sensitive;

    @Column(name = "attachment_name", length = 200)
    private String attachmentName;

    @Column(name = "attachment_content_type", length = 100)
    private String attachmentContentType;

    @Column(name = "attachment_data")
    private byte[] attachmentData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Final status reached: drop one-time links from the stored bodies and the attachment bytes. */
    public void finish(Status finalStatus, Instant at) {
        status = finalStatus;
        nextAttemptAt = null;
        if (finalStatus == Status.SENT) {
            sentAt = at;
        }
        if (sensitive) {
            htmlBody = REDACTED_BODY;
            textBody = REDACTED_BODY;
        }
        attachmentData = null;
    }
}

package com.nexus360.statuspage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * An e-mail subscription to status page updates (double opt-in). Only SHA-256 hashes of the confirmation and unsubscribe
 * tokens are stored; an unsubscribe removes the row.
 */
@Getter
@Setter
@Entity
@Table(name = "status_subscribers")
public class StatusSubscriber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(name = "confirm_token_hash", length = 64)
    private String confirmTokenHash;

    @Column(name = "confirm_expires_at")
    private Instant confirmExpiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "unsubscribe_token_hash", nullable = false, length = 64, unique = true)
    private String unsubscribeTokenHash;

    @Column(name = "request_ip", length = 64)
    private String requestIp;

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
}

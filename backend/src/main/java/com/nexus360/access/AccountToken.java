package com.nexus360.access;

import com.nexus360.common.CreatedEntity;
import com.nexus360.user.Department;
import com.nexus360.user.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A single-use invitation or password-reset link. Only the SHA-256 hash of the random token is stored; the raw token
 * appears exactly once, in the administrator's API response.
 */
@Getter
@Setter
@Entity
@Table(name = "account_tokens")
public class AccountToken extends CreatedEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountTokenPurpose purpose;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** Normalised to lower case. */
    @Column(nullable = false, length = 160)
    private String email;

    @Column(name = "full_name", length = 120)
    private String fullName;

    /** Role granted on registration (invitations only). */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "job_title", length = 120)
    private String jobTitle;

    /** Target account of a reset link; the registered account once an invitation has been used. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "account_request_id")
    private Long accountRequestId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by_id")
    private Long createdById;

    /** Not used, not revoked and not expired. */
    public boolean isUsable(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}

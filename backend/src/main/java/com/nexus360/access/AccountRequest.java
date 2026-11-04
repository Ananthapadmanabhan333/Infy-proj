package com.nexus360.access;

import com.nexus360.common.CreatedEntity;
import com.nexus360.user.User;
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
 * A request submitted from the public pages: an access request (new account) or a password reset request. User
 * administrators decide it; approving issues a single-use {@link AccountToken} link.
 */
@Getter
@Setter
@Entity
@Table(name = "account_requests")
public class AccountRequest extends CreatedEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountRequestType type;

    /** Normalised to lower case. */
    @Column(nullable = false, length = 160)
    private String email;

    @Column(name = "full_name", length = 120)
    private String fullName;

    /** Free-text department given by the requester (the administrator picks the real department on approval). */
    @Column(name = "department_text", length = 120)
    private String departmentText;

    @Column(name = "job_title", length = 120)
    private String jobTitle;

    @Column(length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountRequestStatus status = AccountRequestStatus.PENDING;

    /** The account concerned: the target of a password reset, or the account created from an access request. */
    @Column(name = "user_id")
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_id")
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "request_ip", length = 64)
    private String requestIp;
}

package com.nexus360.approval;

import com.nexus360.common.CreatedEntity;
import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Out-of-office delegation: tasks assigned to the delegator personally are routed to the delegate during the window. */
@Getter
@Setter
@Entity
@Table(name = "approval_delegations")
public class ApprovalDelegation extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delegator_id")
    private User delegator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delegate_id")
    private User delegate;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(length = 500)
    private String reason;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean activeAt(Instant now) {
        return revokedAt == null && !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
}

package com.nexus360.oncall;

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

/** A period in which a person is not available for on-call duty (the rotation passes to the next available participant). */
@Getter
@Setter
@Entity
@Table(name = "oncall_unavailability")
public class OnCallUnavailability extends com.nexus360.common.CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(length = 300)
    private String reason;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;
}

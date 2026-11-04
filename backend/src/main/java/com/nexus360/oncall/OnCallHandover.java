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

/** A handover note left on a schedule for the next person on call. */
@Getter
@Setter
@Entity
@Table(name = "oncall_handovers")
public class OnCallHandover extends com.nexus360.common.CreatedEntity {

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "author_id")
    private Long authorId;

    @Column(name = "author_name", nullable = false, length = 160)
    private String authorName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_user_id")
    private User toUser;

    @Column(name = "shift_start")
    private Instant shiftStart;

    @Column(name = "shift_end")
    private Instant shiftEnd;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;
}

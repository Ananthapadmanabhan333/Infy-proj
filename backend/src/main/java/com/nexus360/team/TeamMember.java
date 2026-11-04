package com.nexus360.team;

import com.nexus360.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Membership of a staff user in a team; {@code LEAD} members are team leads. */
@Getter
@Setter
@Entity
@Table(name = "team_members")
public class TeamMember {

    public enum MemberRole { MEMBER, LEAD }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "team_id", nullable = false)
        private Long teamId;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        public Key(Long teamId, Long userId) {
            this.teamId = teamId;
            this.userId = userId;
        }
    }

    @EmbeddedId
    private Key id = new Key();

    @MapsId("teamId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id")
    private Team team;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false, length = 10)
    private MemberRole memberRole = MemberRole.MEMBER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public static TeamMember of(Team team, User user, MemberRole role) {
        TeamMember m = new TeamMember();
        m.setId(new Key(team.getId(), user.getId()));
        m.setTeam(team);
        m.setUser(user);
        m.setMemberRole(role);
        return m;
    }
}

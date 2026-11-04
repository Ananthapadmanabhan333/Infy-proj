package com.nexus360.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One user's choice for one notification category; absent rows mean "enabled". */
@Getter
@Setter
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "user_id", nullable = false)
        private Long userId;

        @Column(nullable = false, length = 40)
        private String type;

        public Key(Long userId, String type) {
            this.userId = userId;
            this.type = type;
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "in_app", nullable = false)
    private boolean inApp = true;

    /** E-mail channel; {@code null} = the category's default ({@link NotificationTypes#emailDefault}). */
    @Column(name = "email")
    private Boolean email;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

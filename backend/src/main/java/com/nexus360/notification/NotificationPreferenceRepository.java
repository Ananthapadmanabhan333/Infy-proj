package com.nexus360.notification;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, NotificationPreference.Key> {

    @Query("select p from NotificationPreference p where p.id.userId = :userId")
    List<NotificationPreference> findByUserId(@Param("userId") Long userId);

    /** Users (among {@code userIds}) who explicitly switched the category's e-mail channel on or off. */
    @Query("select p.id.userId from NotificationPreference p where p.id.type = :type and p.email = :enabled and p.id.userId in :userIds")
    List<Long> findEmailChoice(@Param("type") String type, @Param("enabled") boolean enabled, @Param("userIds") Collection<Long> userIds);

    /** Users (among {@code userIds}) who switched the category off. */
    @Query("select p.id.userId from NotificationPreference p where p.id.type = :type and p.inApp = false and p.id.userId in :userIds")
    List<Long> findDisabledUsers(@Param("type") String type, @Param("userIds") Collection<Long> userIds);
}

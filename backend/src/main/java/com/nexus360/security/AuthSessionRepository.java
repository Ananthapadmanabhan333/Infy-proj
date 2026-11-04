package com.nexus360.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findByFamilyId(UUID familyId);

    Optional<AuthSession> findByIdAndUserId(Long id, Long userId);

    @Query("select s from AuthSession s where s.user.id = :userId and s.revokedAt is null and s.expiresAt > :now order by s.lastUsedAt desc")
    List<AuthSession> findActive(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :now, s.revokedReason = :reason where s.user.id = :userId and s.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now, @Param("reason") String reason);

    @Modifying
    @Query("delete from AuthSession s where (s.expiresAt < :cutoff) or (s.revokedAt is not null and s.revokedAt < :cutoff)")
    int deleteEndedBefore(@Param("cutoff") Instant cutoff);
}

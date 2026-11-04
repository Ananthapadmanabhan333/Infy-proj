package com.nexus360.access;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    @EntityGraph(attributePaths = "department")
    Optional<AccountToken> findByTokenHashAndPurpose(String tokenHash, AccountTokenPurpose purpose);

    /** Row lock that makes consuming a link single-use under concurrent submissions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AccountToken t where t.tokenHash = :hash and t.purpose = :purpose")
    Optional<AccountToken> findForUpdate(@Param("hash") String tokenHash, @Param("purpose") AccountTokenPurpose purpose);

    /** Revokes every unused, unrevoked link of the purpose for the e-mail address. */
    @Modifying(flushAutomatically = true)
    @Query("update AccountToken t set t.revokedAt = :now where lower(t.email) = lower(:email) and t.purpose = :purpose "
            + "and t.usedAt is null and t.revokedAt is null")
    int revokeOpen(@Param("email") String email, @Param("purpose") AccountTokenPurpose purpose, @Param("now") Instant now);
}

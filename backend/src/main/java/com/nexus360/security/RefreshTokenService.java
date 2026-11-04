package com.nexus360.security;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.RequestInfo;
import com.nexus360.config.AppProperties;
import com.nexus360.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rotating refresh tokens grouped into sessions. Each sign-in creates an {@link AuthSession}; each refresh returns a new
 * token of the same family. Presenting an already-rotated token (outside a short race window for parallel tabs) is
 * treated as theft and revokes the whole family and its session. Sessions without "remember me" end
 * {@code BROWSER_SESSION_HOURS} after sign-in; remember-me sessions slide by {@code REFRESH_TOKEN_DAYS} on every refresh.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    static final Duration REUSE_GRACE = Duration.ofSeconds(20);

    /** Reasons recorded on revoked sessions. */
    public static final String REASON_LOGOUT = "LOGOUT";
    public static final String REASON_REVOKED = "REVOKED";
    public static final String REASON_REVOKED_BY_ADMIN = "REVOKED_BY_ADMIN";
    public static final String REASON_REUSE = "REUSE_DETECTED";
    public static final String REASON_ACCOUNT_CHANGED = "ACCOUNT_CHANGED";

    private final RefreshTokenRepository repository;
    private final AuthSessionRepository sessions;
    private final AppProperties properties;
    private final AuditService audit;
    private final Clock clock;

    public record Issued(String rawToken, AuthSession session) {}

    public record Rotation(String rawToken, User user, AuthSession session) {}

    /** A remember-me session (the Release 2 behaviour). */
    @Transactional
    public String issue(User user) {
        return issue(user, true).rawToken();
    }

    @Transactional
    public Issued issue(User user, boolean rememberMe) {
        Instant now = clock.instant();
        AuthSession session = new AuthSession();
        session.setUser(user);
        session.setFamilyId(UUID.randomUUID());
        session.setRememberMe(rememberMe);
        session.setUserAgent(RequestInfo.userAgent());
        session.setIpAddress(RequestInfo.clientIp());
        session.setLastIpAddress(session.getIpAddress());
        session.setCreatedAt(now);
        session.setLastUsedAt(now);
        session.setExpiresAt(rememberMe ? now.plus(refreshLifetime()) : now.plus(Duration.ofHours(properties.security().browserSessionHours())));
        sessions.save(session);
        return new Issued(create(user, session), session);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw unauthorized();
        }
        Instant now = clock.instant();
        RefreshToken current = repository.findByTokenHash(Hashing.sha256Hex(rawToken)).orElseThrow(RefreshTokenService::unauthorized);
        User user = current.getUser();
        AuthSession session = sessions.findByFamilyId(current.getFamilyId()).orElseGet(() -> legacySession(current, now));
        if (current.getRevokedAt() != null) {
            boolean benignRace = current.getReplacedByHash() != null && session.getRevokedAt() == null
                    && current.getRevokedAt().isAfter(now.minus(REUSE_GRACE));
            if (!benignRace) {
                repository.revokeFamily(current.getFamilyId(), now);
                if (session.getRevokedAt() == null) {
                    session.revoke(now, REASON_REUSE);
                    audit.recordAs(user.getId(), user.getEmail(), "AUTH_REFRESH_REUSE_DETECTED", "USER", user.getId(),
                            Map.of("family", current.getFamilyId().toString(), "sessionId", session.getId()), AuditService.DENIED);
                    log.warn("Refresh token reuse detected for user {}; family revoked", user.getId());
                }
                throw unauthorized();
            }
        }
        if (current.getExpiresAt().isBefore(now) || !user.isActive() || !session.isActive(now)) {
            throw unauthorized();
        }
        session.setLastUsedAt(now);
        String ip = RequestInfo.clientIp();
        if (ip != null) {
            session.setLastIpAddress(ip);
        }
        if (session.isRememberMe()) {
            session.setExpiresAt(now.plus(refreshLifetime()));
        }
        String next = create(user, session);
        if (current.getRevokedAt() == null) {
            current.setRevokedAt(now);
            current.setReplacedByHash(Hashing.sha256Hex(next));
        }
        return new Rotation(next, user, session);
    }

    /** Sign-out: revokes the presented token's family and ends its session. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(Hashing.sha256Hex(rawToken)).ifPresent(token -> {
            Instant now = clock.instant();
            repository.revokeFamily(token.getFamilyId(), now);
            sessions.findByFamilyId(token.getFamilyId()).ifPresent(s -> s.revoke(now, REASON_LOGOUT));
        });
    }

    /** The session a raw refresh token belongs to (for "current session" detection), if any. */
    @Transactional(readOnly = true)
    public java.util.Optional<AuthSession> sessionOf(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return java.util.Optional.empty();
        }
        return repository.findByTokenHash(Hashing.sha256Hex(rawToken)).flatMap(t -> sessions.findByFamilyId(t.getFamilyId()));
    }

    /** Ends one session and revokes its refresh-token family. */
    @Transactional
    public void revokeSession(AuthSession session, String reason) {
        Instant now = clock.instant();
        repository.revokeFamily(session.getFamilyId(), now);
        session.revoke(now, reason);
    }

    /** Signs a user out everywhere (password reset, role change, deactivation, administrator action). */
    @Transactional
    public int revokeAll(Long userId) {
        return revokeAll(userId, REASON_ACCOUNT_CHANGED);
    }

    @Transactional
    public int revokeAll(Long userId, String reason) {
        Instant now = clock.instant();
        repository.revokeAllForUser(userId, now);
        return sessions.revokeAllForUser(userId, now, reason);
    }

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(30));
        int removed = repository.deleteExpiredBefore(cutoff);
        int ended = sessions.deleteEndedBefore(cutoff);
        if (removed + ended > 0) {
            log.info("Purged {} expired refresh tokens and {} ended sessions", removed, ended);
        }
    }

    private String create(User user, AuthSession session) {
        String raw = Hashing.randomToken(32);
        Instant now = clock.instant();
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(Hashing.sha256Hex(raw));
        token.setFamilyId(session.getFamilyId());
        Instant expiry = now.plus(refreshLifetime());
        token.setExpiresAt(expiry.isAfter(session.getExpiresAt()) ? session.getExpiresAt() : expiry);
        token.setIpAddress(RequestInfo.clientIp());
        token.setUserAgent(RequestInfo.userAgent());
        repository.save(token);
        return raw;
    }

    /** A token family created before sessions existed (V6 back-fills them; this covers races during the upgrade). */
    private AuthSession legacySession(RefreshToken token, Instant now) {
        AuthSession s = new AuthSession();
        s.setUser(token.getUser());
        s.setFamilyId(token.getFamilyId());
        s.setRememberMe(true);
        s.setUserAgent(token.getUserAgent());
        s.setIpAddress(token.getIpAddress());
        s.setLastIpAddress(token.getIpAddress());
        s.setCreatedAt(token.getCreatedAt() == null ? now : token.getCreatedAt());
        s.setLastUsedAt(now);
        s.setExpiresAt(token.getExpiresAt());
        return sessions.save(s);
    }

    private Duration refreshLifetime() {
        return Duration.ofDays(properties.security().refreshTokenDays());
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Session expired. Please sign in again.");
    }
}

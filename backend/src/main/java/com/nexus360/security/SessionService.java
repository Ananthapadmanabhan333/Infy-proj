package com.nexus360.security;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session management (docs/RELEASE3_SPEC.md §4): users list and end their own sessions, administrators sign a user out
 * everywhere. Ending a session revokes its refresh-token family; an already issued access token stays valid until it
 * expires ({@code ACCESS_TOKEN_MINUTES}, 15 by default) because access tokens are verified without a database lookup.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    /**
     * {@code device}: browser and operating system derived from the user agent ("Chrome on Windows"); {@code current}: the
     * session of this request (from the access token's {@code sid} claim, else the refresh cookie).
     */
    public record SessionDto(Long id, String device, String browser, String os, String userAgent, String ipAddress, String lastIpAddress,
                             Instant createdAt, Instant lastUsedAt, Instant expiresAt, boolean rememberMe, boolean current) {}

    private final AuthSessionRepository sessions;
    private final RefreshTokenService refreshTokens;
    private final UserRepository users;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<SessionDto> mine(Actor actor, String refreshCookie) {
        Long current = currentSessionId(refreshCookie);
        return sessions.findActive(actor.id(), clock.instant()).stream().map(s -> dto(s, Objects.equals(s.getId(), current))).toList();
    }

    /** {@code true} when the ended session was the caller's current one. */
    @Transactional
    public boolean revokeOwn(Long sessionId, Actor actor, String refreshCookie) {
        AuthSession session = sessions.findByIdAndUserId(sessionId, actor.id())
                .filter(s -> s.isActive(clock.instant()))
                .orElseThrow(() -> ApiException.notFound("Session", sessionId));
        refreshTokens.revokeSession(session, RefreshTokenService.REASON_REVOKED);
        audit.record("SESSION_REVOKED", "SESSION", session.getId(), Map.of("userId", actor.id()));
        return Objects.equals(session.getId(), currentSessionId(refreshCookie));
    }

    @Transactional
    public int revokeOthers(Actor actor, String refreshCookie) {
        Long current = currentSessionId(refreshCookie);
        if (current == null) {
            throw ApiException.badRequest("The current session could not be identified; sign in again first");
        }
        int count = 0;
        for (AuthSession s : sessions.findActive(actor.id(), clock.instant())) {
            if (!s.getId().equals(current)) {
                refreshTokens.revokeSession(s, RefreshTokenService.REASON_REVOKED);
                count++;
            }
        }
        audit.record("SESSIONS_REVOKED_OTHERS", "USER", actor.id(), Map.of("revoked", count, "keptSessionId", current));
        return count;
    }

    @Transactional(readOnly = true)
    public List<SessionDto> ofUser(Long userId) {
        users.findById(userId).orElseThrow(() -> ApiException.notFound("User", userId));
        return sessions.findActive(userId, clock.instant()).stream().map(s -> dto(s, false)).toList();
    }

    /** Administrators sign a user out of every session; IT administrators cannot do this to system administrators. */
    @Transactional
    public int revokeAllOf(Long userId, Actor actor) {
        User target = users.findById(userId).orElseThrow(() -> ApiException.notFound("User", userId));
        if (target.getRole() == Role.SYSTEM_ADMIN && !actor.isSystem() && actor.role() != Role.SYSTEM_ADMIN) {
            throw ApiException.forbidden("Only a system administrator can end a system administrator's sessions");
        }
        int count = refreshTokens.revokeAll(userId, RefreshTokenService.REASON_REVOKED_BY_ADMIN);
        audit.record("USER_SESSIONS_REVOKED", "USER", userId, Map.of("revoked", count, "email", target.getEmail()));
        return count;
    }

    private Long currentSessionId(String refreshCookie) {
        Long sid = CurrentActor.sessionId();
        if (sid != null) {
            return sid;
        }
        return refreshTokens.sessionOf(refreshCookie).map(AuthSession::getId).orElse(null);
    }

    private static SessionDto dto(AuthSession s, boolean current) {
        String browser = browser(s.getUserAgent());
        String os = os(s.getUserAgent());
        String device = browser == null && os == null ? "Unknown device"
                : (browser == null ? "Browser" : browser) + (os == null ? "" : " on " + os);
        return new SessionDto(s.getId(), device, browser, os, s.getUserAgent(), s.getIpAddress(), s.getLastIpAddress(), s.getCreatedAt(),
                s.getLastUsedAt(), s.getExpiresAt(), s.isRememberMe(), current);
    }

    static String browser(String ua) {
        if (ua == null || ua.isBlank()) {
            return null;
        }
        String u = ua.toLowerCase(Locale.ROOT);
        if (u.contains("edg/") || u.contains("edge/")) {
            return "Edge";
        }
        if (u.contains("opr/") || u.contains("opera")) {
            return "Opera";
        }
        if (u.contains("firefox/")) {
            return "Firefox";
        }
        if (u.contains("chrome/") || u.contains("crios/")) {
            return "Chrome";
        }
        if (u.contains("safari/")) {
            return "Safari";
        }
        if (u.contains("curl/") || u.contains("postman") || u.contains("java/") || u.contains("python")) {
            return "API client";
        }
        return null;
    }

    static String os(String ua) {
        if (ua == null || ua.isBlank()) {
            return null;
        }
        String u = ua.toLowerCase(Locale.ROOT);
        if (u.contains("windows")) {
            return "Windows";
        }
        if (u.contains("iphone") || u.contains("ipad") || u.contains("ios")) {
            return "iOS";
        }
        if (u.contains("android")) {
            return "Android";
        }
        if (u.contains("mac os") || u.contains("macintosh")) {
            return "macOS";
        }
        if (u.contains("cros")) {
            return "ChromeOS";
        }
        if (u.contains("linux")) {
            return "Linux";
        }
        return null;
    }
}

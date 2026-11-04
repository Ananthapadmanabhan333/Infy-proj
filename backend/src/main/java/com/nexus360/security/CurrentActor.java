package com.nexus360.security;

import com.nexus360.common.ApiException;
import com.nexus360.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Reads the authenticated actor from the validated JWT (no database round trip). */
public final class CurrentActor {

    private CurrentActor() {}

    public static Actor require() {
        Actor actor = fromContext();
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return actor;
    }

    public static Actor orSystem() {
        Actor actor = fromContext();
        return actor == null ? Actor.SYSTEM : actor;
    }

    /** The session id ({@code sid} claim) of the current access token; null for tokens issued before Release 3. */
    public static Long sessionId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            Object sid = token.getToken().getClaims().get(JwtService.SESSION_CLAIM);
            if (sid instanceof Number n) {
                return n.longValue();
            }
            if (sid instanceof String s && !s.isBlank()) {
                try {
                    return Long.valueOf(s);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Actor fromContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            return new Actor(Long.valueOf(jwt.getSubject()), jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("name"), Role.fromName(jwt.getClaimAsString("role")), 0);
        }
        return null;
    }
}

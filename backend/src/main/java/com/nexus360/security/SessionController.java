package com.nexus360.security;

import com.nexus360.config.AppProperties;
import com.nexus360.security.SessionService.SessionDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Own sessions ({@code /api/auth/sessions}) and the administrators' "sign out everywhere" ({@code /api/users/{id}/sessions}). */
@Tag(name = "Sessions")
@RestController
@RequiredArgsConstructor
public class SessionController {

    private final SessionService service;
    private final AppProperties properties;

    @Operation(summary = "My active sessions (device, IP, created, last used, current)")
    @GetMapping("/api/auth/sessions")
    public List<SessionDto> mine(@CookieValue(name = AuthController.REFRESH_COOKIE, required = false) String refreshCookie) {
        return service.mine(CurrentActor.require(), refreshCookie);
    }

    @Operation(summary = "End one of my sessions (its refresh tokens are revoked); ending the current one also clears the cookie")
    @DeleteMapping("/api/auth/sessions/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id,
                                       @CookieValue(name = AuthController.REFRESH_COOKIE, required = false) String refreshCookie) {
        boolean current = service.revokeOwn(id, CurrentActor.require(), refreshCookie);
        ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
        if (current) {
            response.header(HttpHeaders.SET_COOKIE, AuthController.clearCookie(properties).toString());
        }
        return response.build();
    }

    @Operation(summary = "End all my sessions except the current one")
    @PostMapping("/api/auth/sessions/revoke-others")
    public Map<String, Integer> revokeOthers(@CookieValue(name = AuthController.REFRESH_COOKIE, required = false) String refreshCookie) {
        return Map.of("revoked", service.revokeOthers(CurrentActor.require(), refreshCookie));
    }

    @PreAuthorize(Roles.USER_ADMINS)
    @GetMapping("/api/users/{id}/sessions")
    public List<SessionDto> ofUser(@PathVariable Long id) {
        return service.ofUser(id);
    }

    @Operation(summary = "Sign a user out of every session (user administrators)")
    @PreAuthorize(Roles.USER_ADMINS)
    @PostMapping("/api/users/{id}/sessions/revoke")
    public Map<String, Integer> revokeAll(@PathVariable Long id) {
        return Map.of("revoked", service.revokeAllOf(id, CurrentActor.require()));
    }
}

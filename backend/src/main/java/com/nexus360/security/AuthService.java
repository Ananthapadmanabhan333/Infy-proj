package com.nexus360.security;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.config.AppProperties;
import com.nexus360.security.AuthDtos.CurrentUser;
import com.nexus360.security.AuthDtos.LoginResponse;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    /** Used to keep response timing similar for unknown e-mail addresses. */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWX4Z3JxXmyBR1W6bL9.mvU5jGxW";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final AppProperties properties;
    private final com.nexus360.admin.SettingsService settings;
    private final Clock clock;

    /** {@code rememberMe} decides whether the refresh cookie is persistent (Max-Age) or a browser-session cookie. */
    public record Session(LoginResponse response, String refreshToken, boolean rememberMe) {}

    @Transactional(noRollbackFor = ApiException.class)
    public Session login(String email, String password) {
        return login(email, password, true);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session login(String email, String password, boolean rememberMe) {
        Instant now = clock.instant();
        User user = users.findByEmailIgnoreCase(email.trim()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, DUMMY_HASH);
            audit.recordAs(null, email, "AUTH_LOGIN_FAILED", "USER", null, Map.of("reason", "unknown_email"), AuditService.FAILURE);
            throw invalidCredentials();
        }
        if (user.isLocked(now)) {
            audit.recordAs(user.getId(), user.getEmail(), "AUTH_LOGIN_BLOCKED", "USER", user.getId(), Map.of("reason", "locked"), AuditService.DENIED);
            throw new ApiException(HttpStatus.LOCKED, "Account temporarily locked after repeated failed sign-in attempts. Try again later.");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            if (attempts >= properties.security().maxFailedLogins()) {
                user.setLockedUntil(now.plus(Duration.ofMinutes(properties.security().lockoutMinutes())));
                user.setFailedLoginAttempts(0);
                audit.recordAs(user.getId(), user.getEmail(), "AUTH_ACCOUNT_LOCKED", "USER", user.getId(), Map.of("attempts", attempts), AuditService.DENIED);
            } else {
                user.setFailedLoginAttempts(attempts);
            }
            audit.recordAs(user.getId(), user.getEmail(), "AUTH_LOGIN_FAILED", "USER", user.getId(), Map.of("reason", "bad_password"), AuditService.FAILURE);
            throw invalidCredentials();
        }
        if (!user.isActive()) {
            audit.recordAs(user.getId(), user.getEmail(), "AUTH_LOGIN_BLOCKED", "USER", user.getId(), Map.of("reason", "inactive"), AuditService.DENIED);
            throw invalidCredentials();
        }
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        RefreshTokenService.Issued issued = refreshTokens.issue(user, rememberMe);
        audit.recordAs(user.getId(), user.getEmail(), "AUTH_LOGIN", "USER", user.getId(),
                Map.of("sessionId", issued.session().getId(), "rememberMe", rememberMe), AuditService.SUCCESS);
        return new Session(response(user, issued.session().getId()), issued.rawToken(), rememberMe);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
        return new Session(response(rotation.user(), rotation.session().getId()), rotation.rawToken(), rotation.session().isRememberMe());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
        Actor actor = CurrentActor.orSystem();
        if (!actor.isSystem()) {
            audit.record("AUTH_LOGOUT", "USER", actor.id(), null);
        }
    }

    @Transactional(readOnly = true)
    public CurrentUser me() {
        Actor actor = CurrentActor.require();
        return users.findWithDepartmentById(actor.id()).map(u -> CurrentUser.of(u, organisationName()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    @Transactional
    public void changePassword(String currentPassword, String newPassword) {
        Actor actor = CurrentActor.require();
        User user = users.findById(actor.id()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            audit.record("AUTH_PASSWORD_CHANGE_FAILED", "USER", user.getId(), Map.of("reason", "bad_current_password"));
            throw ApiException.badRequest("Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("New password must differ from the current password");
        }
        PasswordPolicy.validate(newPassword, user.getEmail());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        audit.record("AUTH_PASSWORD_CHANGED", "USER", user.getId(), null);
    }

    private LoginResponse response(User user, Long sessionId) {
        return new LoginResponse(jwtService.issue(user, sessionId), jwtService.lifetimeSeconds(), CurrentUser.of(user, organisationName()));
    }

    private String organisationName() {
        return settings.getString(com.nexus360.admin.SettingsService.ORG_NAME, "SA");
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }
}

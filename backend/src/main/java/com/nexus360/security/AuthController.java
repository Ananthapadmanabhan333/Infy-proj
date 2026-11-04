package com.nexus360.security;

import com.nexus360.common.ApiException;
import com.nexus360.config.AppProperties;
import com.nexus360.security.AuthDtos.ChangePasswordRequest;
import com.nexus360.security.AuthDtos.CurrentUser;
import com.nexus360.security.AuthDtos.DemoAccount;
import com.nexus360.security.AuthDtos.DemoAccountsResponse;
import com.nexus360.security.AuthDtos.LoginRequest;
import com.nexus360.security.AuthDtos.LoginResponse;
import com.nexus360.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    public static final String REFRESH_COOKIE = "nexus_refresh";
    /** Custom header required on cookie-authenticated endpoints; it forces a CORS preflight, blocking cross-site forgery. */
    public static final String CSRF_HEADER = "X-Requested-With";
    public static final String CSRF_HEADER_VALUE = "nexus360";

    private final AuthService authService;
    private final AppProperties properties;
    private final UserRepository users;

    @Operation(summary = "Sign in with e-mail and password")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.Session session = authService.login(request.email(), request.password(), request.remember());
        return withCookie(session);
    }

    @Operation(summary = "Exchange the refresh cookie for a new access token (rotates the refresh token)")
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                                 HttpServletRequest request) {
        requireCsrfHeader(request);
        try {
            return withCookie(authService.refresh(refreshToken));
        } catch (ApiException ex) {
            return ResponseEntity.status(ex.getStatus()).header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
        }
    }

    @Operation(summary = "Sign out and revoke the refresh token")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                       HttpServletRequest request) {
        requireCsrfHeader(request);
        authService.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    @GetMapping("/me")
    public CurrentUser me() {
        return authService.me();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Demo accounts for the login screen (empty unless demo seeding is enabled)")
    @GetMapping("/demo-accounts")
    @Transactional(readOnly = true)
    public DemoAccountsResponse demoAccounts() {
        if (!properties.demo().seed()) {
            return new DemoAccountsResponse(false, List.of(), null);
        }
        List<DemoAccount> accounts = users.findByDemoTrueAndActiveTrueOrderByRoleAscFullNameAsc().stream()
                .map(u -> new DemoAccount(u.getEmail(), u.getFullName(), u.getRole(),
                        u.getDepartment() == null ? null : u.getDepartment().getName()))
                .toList();
        return new DemoAccountsResponse(true, accounts, properties.demo().showPassword() ? properties.demo().password() : null);
    }

    /**
     * Remember-me sessions get a persistent cookie (Max-Age = REFRESH_TOKEN_DAYS); other sessions a browser-session cookie
     * without Max-Age/Expires, which the browser drops when it closes (the server also ends the session after 12 hours).
     */
    private ResponseEntity<LoginResponse> withCookie(AuthService.Session session) {
        ResponseCookie.ResponseCookieBuilder builder = baseCookie(session.refreshToken());
        if (session.rememberMe()) {
            builder.maxAge(Duration.ofDays(properties.security().refreshTokenDays()));
        }
        ResponseCookie cookie = builder.build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(session.response());
    }

    static ResponseCookie clearCookie(AppProperties properties) {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(properties.security().cookieSecure())
                .sameSite(properties.security().cookieSameSite()).path("/api/auth").maxAge(0).build();
    }

    private ResponseCookie clearCookie() {
        return baseCookie("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.security().cookieSecure())
                .sameSite(properties.security().cookieSameSite())
                .path("/api/auth");
    }

    private static void requireCsrfHeader(HttpServletRequest request) {
        if (!CSRF_HEADER_VALUE.equals(request.getHeader(CSRF_HEADER))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Missing " + CSRF_HEADER + " header");
        }
    }
}

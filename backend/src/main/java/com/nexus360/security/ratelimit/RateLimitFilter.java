package com.nexus360.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.common.GlobalExceptionHandler;
import com.nexus360.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Per-IP limits: strict on credential endpoints, generous on the general API, per-integration on inbound alerts. */
public class RateLimitFilter extends OncePerRequestFilter {

    /** Public account-access POSTs; they share the sign-in bucket ({@code login:<ip>}, {@code loginPerMinute}). */
    public static final Set<String> PUBLIC_ACCOUNT_POSTS = Set.of(
            "/api/auth/access-requests", "/api/auth/forgot-password", "/api/auth/register", "/api/auth/reset-password");

    /** Public status-page POSTs (Release 3); they share the sign-in bucket too. */
    public static final Set<String> PUBLIC_STATUS_POSTS = Set.of(
            "/api/public/status/subscribe", "/api/public/status/confirm", "/api/public/status/unsubscribe");

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final AppProperties.RateLimit config;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimiter limiter, AppProperties.RateLimit config, ObjectMapper mapper) {
        this.limiter = limiter;
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!config.enabled() || "OPTIONS".equals(request.getMethod()) || !path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String ip = request.getRemoteAddr();
        String key;
        int limit;
        if (path.equals("/api/auth/login") || path.equals("/api/auth/change-password")
                || ("POST".equals(request.getMethod()) && (PUBLIC_ACCOUNT_POSTS.contains(path) || PUBLIC_STATUS_POSTS.contains(path)))) {
            key = "login:" + ip;
            limit = config.loginPerMinute();
        } else if (path.startsWith("/api/auth/refresh")) {
            key = "refresh:" + ip;
            limit = Math.max(30, config.loginPerMinute() * 3);
        } else if (path.startsWith("/api/integrations/inbound/") || path.startsWith("/api/deployments/webhooks/")
                || path.startsWith("/api/alerts/webhooks/") || path.startsWith("/api/otel/")) {
            key = "inbound:" + path;
            limit = config.inboundPerMinute();
        } else {
            key = "api:" + ip;
            limit = config.apiPerMinute();
        }
        RateLimiter.Decision decision = limiter.tryAcquire(key, limit, MINUTE);
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.error(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests. Retry in " + decision.retryAfterSeconds() + " seconds.", path));
            return;
        }
        chain.doFilter(request, response);
    }
}

package com.nexus360.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.common.GlobalExceptionHandler;
import com.nexus360.security.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-user budget for SA AI calls ({@code RATE_LIMIT_AI_PER_MINUTE}, default 30; 0 disables). Runs after bearer
 * authentication so the key is the user, not the client address, and applies to every AI invocation (POST under
 * {@code /api/ai/}, and since Release 4 {@code POST /api/agents/runs} and {@code POST /api/agents/evaluations/run}); the read-only status
 * and history endpoints are not counted. Independent of the per-IP limits
 * because it protects LLM cost as well as capacity.
 */
public class AiRateLimitFilter extends OncePerRequestFilter {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final int perMinute;
    private final ObjectMapper mapper;

    public AiRateLimitFilter(RateLimiter limiter, int perMinute, ObjectMapper mapper) {
        this.limiter = limiter;
        this.perMinute = perMinute;
        this.mapper = mapper;
    }

    /** SA AI invocations and (Release 4) SA agent runs and evaluation runs share the per-user budget. */
    static boolean limited(String path) {
        return path.startsWith("/api/ai/") || path.equals("/api/agents/runs") || path.equals("/api/agents/evaluations/run");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (perMinute <= 0 || !"POST".equals(request.getMethod()) || !limited(path) || !(auth instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        RateLimiter.Decision decision = limiter.tryAcquire("ai:user:" + token.getToken().getSubject(), perMinute, MINUTE);
        response.setHeader("X-AI-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.error(HttpStatus.TOO_MANY_REQUESTS,
                    "SA AI request limit of " + perMinute + " per minute reached. Retry in " + decision.retryAfterSeconds()
                            + " seconds.", path));
            return;
        }
        chain.doFilter(request, response);
    }
}

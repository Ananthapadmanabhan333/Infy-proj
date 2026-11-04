package com.nexus360.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.audit.AuditService;
import com.nexus360.common.GlobalExceptionHandler;
import com.nexus360.config.AppProperties;
import com.nexus360.security.ratelimit.InMemoryRateLimiter;
import com.nexus360.security.ratelimit.RateLimitFilter;
import com.nexus360.security.ratelimit.RateLimiter;
import com.nexus360.security.ratelimit.RedisRateLimiter;
import com.nexus360.user.Role;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final AppProperties properties;
    private final ObjectMapper mapper;

    public SecurityConfig(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, RateLimiter rateLimiter,
                                            AuditService audit) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, ex) ->
                writeError(response, HttpStatus.UNAUTHORIZED, "Authentication required", request.getRequestURI());
        AccessDeniedHandler deniedHandler = (request, response, ex) -> {
            Actor actor = CurrentActor.orSystem();
            audit.recordAs(actor.id(), actor.email(), "ACCESS_DENIED", "HTTP", null,
                    Map.of("method", request.getMethod(), "path", request.getRequestURI()), AuditService.DENIED);
            writeError(response, HttpStatus.FORBIDDEN, "You do not have permission to perform this action", request.getRequestURI());
        };

        http
                .csrf(csrf -> csrf.disable()) // stateless bearer tokens; cookie endpoints require X-Requested-With (see AuthController)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .authorizeHttpRequests(auth -> auth
                        // Async dispatches (Server-Sent Events) continue a request that was already authorised.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/demo-accounts", "/api/health").permitAll()
                        // Release 2.1 public account access (docs/PUBLIC_ACCESS_SPEC.md §4); the POSTs share the login rate limit.
                        .requestMatchers(HttpMethod.POST, RateLimitFilter.PUBLIC_ACCOUNT_POSTS.toArray(String[]::new)).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/invitations/*", "/api/auth/reset-password/*", "/api/public/info").permitAll()
                        // Release 3 public status page (404 unless published); the POSTs share the login rate limit.
                        .requestMatchers(HttpMethod.GET, "/api/public/status").permitAll()
                        .requestMatchers(HttpMethod.POST, RateLimitFilter.PUBLIC_STATUS_POSTS.toArray(String[]::new)).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/integrations/inbound/*").permitAll()
                        // Release 3 CI/CD webhooks: authenticated by the provider signature/token (unknown or unsigned → 401).
                        .requestMatchers(HttpMethod.POST, "/api/deployments/webhooks/*").permitAll()
                        // Release 3 BE-3 alert webhooks: Prometheus/Grafana bearer secret, generic HMAC, CloudWatch SNS signature (else 401).
                        .requestMatchers(HttpMethod.POST, "/api/alerts/webhooks/*").permitAll()
                        // Release 4 BE-5: OTLP/HTTP JSON ingestion, authenticated by the telemetry source token (else 401).
                        .requestMatchers(HttpMethod.POST, "/api/otel/v1/metrics", "/api/otel/v1/logs", "/api/otel/v1/traces").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/security/vulnerabilities/ingest").permitAll()
                        // Coarse role gates evaluated before any request parsing (method security refines them), so
                        // unauthorised callers cannot probe validation rules of privileged endpoints.
                        .requestMatchers("/api/admin/settings/**", "/api/admin/ai", "/api/admin/demo/**").hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/api/admin/system-health").hasAnyRole(Roles.ADMIN_ROLES)
                        .requestMatchers(HttpMethod.GET, "/api/admin/incident-categories").hasAnyRole(Roles.READER_ROLES)
                        .requestMatchers("/api/admin/incident-categories").hasAnyRole(Roles.ADMIN_ROLES)
                        .requestMatchers("/api/admin/**").hasAnyRole(Roles.ADMIN_ROLES)
                        // Approval engine v2: tasks can be routed to any user (user, role, team approvers), so the inbox is open
                        // to every signed-in user and only returns what is routed to the caller.
                        .requestMatchers("/api/approvals", "/api/approvals/**").authenticated()
                        .requestMatchers("/api/integrations/**").hasAnyRole(Roles.ADMIN_OR_MANAGER_ROLES)
                        .requestMatchers("/api/audit-logs/**").hasAnyRole(Roles.AUDIT_READER_ROLES)
                        .requestMatchers(HttpMethod.POST, "/api/users", "/api/users/*/reset-password", "/api/users/*/sessions/revoke",
                                "/api/departments", "/api/services", "/api/catalog/**", "/api/sla/**").hasAnyRole(Roles.ADMIN_ROLES)
                        .requestMatchers(HttpMethod.GET, "/api/users/*/sessions").hasAnyRole(Roles.ADMIN_ROLES)
                        // Release 3 coarse gates (method security refines them).
                        .requestMatchers("/api/my-work", "/api/my-work/**").hasAnyRole(Roles.STAFF_ROLES)
                        .requestMatchers(HttpMethod.POST, "/api/teams", "/api/teams/**").hasAnyRole(Roles.TEAM_ADMIN_ROLES)
                        .requestMatchers(HttpMethod.PUT, "/api/teams/**").hasAnyRole(Roles.TEAM_ADMIN_ROLES)
                        .requestMatchers(HttpMethod.DELETE, "/api/teams/**").hasAnyRole(Roles.TEAM_ADMIN_ROLES)
                        .requestMatchers(HttpMethod.POST, "/api/status-page/**").hasAnyRole(Roles.STATUS_PAGE_EDITOR_ROLES)
                        .requestMatchers(HttpMethod.PUT, "/api/status-page/**").hasAnyRole(Roles.STATUS_PAGE_EDITOR_ROLES)
                        .requestMatchers(HttpMethod.DELETE, "/api/status-page/**").hasAnyRole(Roles.STATUS_PAGE_EDITOR_ROLES)
                        .requestMatchers(HttpMethod.POST, "/api/reports/schedules").hasAnyRole(Roles.REPORT_EXPORTER_ROLES)
                        .requestMatchers(HttpMethod.PUT, "/api/reports/schedules/*").hasAnyRole(Roles.REPORT_EXPORTER_ROLES)
                        .requestMatchers(HttpMethod.DELETE, "/api/reports/schedules/*").hasAnyRole(Roles.REPORT_EXPORTER_ROLES)
                        // Release 3 BE-2: approval flows, forms, problems, changes, releases and deployments.
                        .requestMatchers(HttpMethod.GET, "/api/approval-flows", "/api/approval-flows/**", "/api/forms", "/api/forms/**",
                                "/api/problems", "/api/problems/**", "/api/changes", "/api/changes/**", "/api/releases", "/api/releases/**",
                                "/api/deployments", "/api/deployments/**").hasAnyRole(Roles.READER_ROLES)
                        .requestMatchers("/api/approval-flows", "/api/approval-flows/**").hasAnyRole(Roles.APPROVAL_FLOW_EDITOR_ROLES)
                        .requestMatchers(HttpMethod.POST, "/api/forms/*/validate").hasAnyRole(Roles.READER_ROLES)
                        .requestMatchers("/api/forms", "/api/forms/**").hasAnyRole(Roles.FORM_EDITOR_ROLES)
                        .requestMatchers("/api/problems", "/api/problems/**").hasAnyRole(Roles.PROBLEM_EDITOR_ROLES)
                        .requestMatchers("/api/changes", "/api/changes/**").hasAnyRole(Roles.STAFF_ROLES)
                        .requestMatchers("/api/releases", "/api/releases/**", "/api/deployments", "/api/deployments/**")
                                .hasAnyRole(Roles.DEPLOYMENT_EDITOR_ROLES)
                        // Release 3 BE-3: assets, CMDB, alerts (sources are configuration: admins only, also for reading).
                        .requestMatchers("/api/alerts/sources", "/api/alerts/sources/**").hasAnyRole(Roles.ADMIN_ROLES)
                        .requestMatchers(HttpMethod.GET, "/api/assets", "/api/assets/**", "/api/cmdb/**", "/api/alerts", "/api/alerts/**")
                                .hasAnyRole(Roles.READER_ROLES)
                        .requestMatchers("/api/assets", "/api/assets/**").hasAnyRole(Roles.ASSET_EDITOR_ROLES)
                        .requestMatchers("/api/cmdb/**").hasAnyRole(Roles.CMDB_EDITOR_ROLES)
                        .requestMatchers("/api/alerts/suppression-rules", "/api/alerts/suppression-rules/**", "/api/alerts/routing-rules",
                                "/api/alerts/routing-rules/**").hasAnyRole(Roles.ADMIN_ROLES)
                        .requestMatchers("/api/alerts", "/api/alerts/**").hasAnyRole(Roles.STAFF_ROLES)
                        // Release 3 BE-4: on-call, major incident command, post-incident reviews and their action items (readers read;
                        // writes are staff at the URL gate and narrowed per method: on-call editors, major incident command, ...).
                        .requestMatchers(HttpMethod.GET, "/api/oncall/**", "/api/major-incidents", "/api/major-incidents/**", "/api/reviews",
                                "/api/reviews/**", "/api/action-items/**").hasAnyRole(Roles.READER_ROLES)
                        .requestMatchers("/api/oncall/**", "/api/major-incidents", "/api/major-incidents/**", "/api/reviews", "/api/reviews/**",
                                "/api/action-items/**").hasAnyRole(Roles.STAFF_ROLES)
                        // Release 4 BE-5: simulation mode (status for every signed-in user: the global banner), observability, twin.
                        .requestMatchers(HttpMethod.GET, "/api/simulation/status").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/simulation/**").hasAnyRole(Roles.READER_ROLES)
                        .requestMatchers("/api/simulation/**").hasAnyRole(Roles.SIMULATION_ADMIN_ROLES)
                        .requestMatchers(HttpMethod.GET, "/api/observability/**").hasAnyRole(Roles.OBSERVABILITY_READER_ROLES)
                        .requestMatchers("/api/observability/correlation-groups/**").hasAnyRole(Roles.STAFF_ROLES)
                        .requestMatchers("/api/observability/**").hasAnyRole(Roles.TELEMETRY_CONFIG_ROLES)
                        .requestMatchers(HttpMethod.GET, "/api/twin/**").hasAnyRole(Roles.OBSERVABILITY_READER_ROLES)
                        .requestMatchers(HttpMethod.POST, "/api/twin/simulations").hasAnyRole(Roles.OBSERVABILITY_READER_ROLES)
                        .requestMatchers("/api/twin/**").hasAnyRole(Roles.TWIN_EDITOR_ROLES)
                        // Release 4 BE-6: predictive intelligence (reads for readers, model lifecycle for prediction administrators) and
                        // SA agents (runs for agent users, policies and evaluations for AI governance; auditors read everything).
                        .requestMatchers(HttpMethod.GET, "/api/predictions/**").hasAnyRole(Roles.OBSERVABILITY_READER_ROLES)
                        .requestMatchers("/api/predictions/**").hasAnyRole(Roles.PREDICTION_ADMIN_ROLES)
                        .requestMatchers(HttpMethod.GET, "/api/agents", "/api/agents/**").hasAnyRole(Roles.AGENT_READER_ROLES)
                        .requestMatchers("/api/agents/policies/**", "/api/agents/evaluations/**").hasAnyRole(Roles.AI_GOVERNANCE_ROLES)
                        .requestMatchers("/api/agents/**").hasAnyRole(Roles.AGENT_USER_ROLES)
                        // Release 4 BE-7: autonomous remediation & security operations.
                        .requestMatchers(HttpMethod.GET, "/api/remediation/systems", "/api/remediation/systems/**")
                                .hasAnyRole(Roles.OBSERVABILITY_READER_ROLES)
                        .requestMatchers("/api/remediation/systems", "/api/remediation/systems/**")
                                .hasAnyRole(Roles.EXECUTION_SYSTEM_ADMIN_ROLES)
                        .requestMatchers("/api/remediation/playbooks/*/versions/*/approve", "/api/remediation/runs/*/override")
                                .hasAnyRole(Roles.PLAYBOOK_APPROVER_ROLES)
                        .requestMatchers(HttpMethod.GET, "/api/remediation/playbooks", "/api/remediation/playbooks/**")
                                .hasAnyRole(Roles.OBSERVABILITY_READER_ROLES)
                        .requestMatchers("/api/remediation/playbooks", "/api/remediation/playbooks/**")
                                .hasAnyRole(Roles.PLAYBOOK_EDITOR_ROLES)
                        .requestMatchers(HttpMethod.GET, "/api/remediation/**").hasAnyRole(Roles.OBSERVABILITY_READER_ROLES)
                        .requestMatchers("/api/remediation/**").hasAnyRole(Roles.REMEDIATION_OPERATOR_ROLES)
                        .requestMatchers(HttpMethod.GET, "/api/security/**").hasAnyRole(Roles.SECOPS_READER_ROLES)
                        .requestMatchers("/api/security/**").hasAnyRole(Roles.SECOPS_ROLES)
                        // Release 4 BE-8: FinOps & Digital Employee Experience (DEX).
                        .requestMatchers(HttpMethod.GET, "/api/finops/**").hasAnyRole(Roles.FINOPS_READER_ROLES)
                        .requestMatchers("/api/finops/**").hasAnyRole(Roles.FINOPS_ROLES)
                        .requestMatchers("/api/dex/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/dex/**").hasAnyRole(Roles.DEX_READER_ROLES)
                        .requestMatchers("/api/dex/**").hasAnyRole(Roles.ADMIN_ROLES)
                        .requestMatchers(HttpMethod.PUT, "/api/users/*", "/api/departments/*", "/api/sla/**", "/api/catalog/**",
                                "/api/services/*").hasAnyRole(Roles.ADMIN_ROLES)
                        .requestMatchers(HttpMethod.DELETE, "/api/sla/**").hasAnyRole(Roles.ADMIN_ROLES)
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .addFilterBefore(new RateLimitFilter(rateLimiter, properties.rateLimit(), mapper), BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new ApiContentSecurityFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new AiRateLimitFilter(rateLimiter, properties.rateLimit().aiPerMinute(), mapper), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtService jwtService) {
        return jwtService.decoder();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    RateLimiter rateLimiter(ObjectProvider<StringRedisTemplate> redis, Clock clock) {
        if (properties.redis().enabled()) {
            return new RedisRateLimiter(redis.getObject());
        }
        return new InMemoryRateLimiter(clock);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.security().allowedOriginList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", AuthController.CSRF_HEADER));
        config.setExposedHeaders(List.of("Content-Disposition", "Retry-After", "X-RateLimit-Remaining"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** Authorities from the "role" claim; Release 1 role names in still-valid tokens are mapped to their successors. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String claim = jwt.getClaimAsString("role");
            if (claim == null || claim.isBlank()) {
                return List.of();
            }
            try {
                return List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + Role.fromName(claim).name()));
            } catch (IllegalArgumentException e) {
                return List.of();
            }
        });
        return converter;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.error(status, message, path));
    }

    /** JSON API responses must never be rendered as documents; Swagger UI keeps its own defaults. */
    static final class ApiContentSecurityFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getRequestURI().startsWith("/api/")) {
                response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Bearer tokens are SA access tokens everywhere except on the alert webhooks, where Prometheus Alertmanager and Grafana send their
     * shared secret as {@code Authorization: Bearer <secret>}; that path is authenticated by {@code AlertWebhookService} instead. The same
     * applies to the OTLP endpoints ({@code /api/otel/**}), where the bearer token is a telemetry source token ({@code OtlpController}).
     */
    static org.springframework.security.oauth2.server.resource.web.BearerTokenResolver bearerTokenResolver() {
        org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver standard =
                new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver();
        return request -> request.getRequestURI() != null && (request.getRequestURI().startsWith("/api/alerts/webhooks/")
                || request.getRequestURI().startsWith("/api/otel/")) ? null : standard.resolve(request);
    }
}

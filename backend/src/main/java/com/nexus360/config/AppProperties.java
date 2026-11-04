package com.nexus360.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Strongly typed application settings bound from {@code app.*}. Invalid values stop the application at startup. */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid Security security,
        @Valid RateLimit rateLimit,
        Redis redis,
        BootstrapAdmin bootstrapAdmin,
        Demo demo,
        @Valid Ai ai,
        Sla sla,
        Workflow workflow,
        Integrations integrations,
        Attachments attachments,
        /** Absolute base URL of the web app used in e-mail links (APP_PUBLIC_URL); falls back to the first FRONTEND_ORIGIN. */
        String publicUrl,
        @Valid Mail mail,
        @Valid Reports reports) {

    /** Base URL for absolute links in e-mails, without a trailing slash (never null; may be empty when nothing is configured). */
    public String publicBaseUrl() {
        String base = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim();
        if (base == null && security != null && !security.allowedOriginList().isEmpty()) {
            base = security.allowedOriginList().get(0);
        }
        if (base == null) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public record Security(
            @NotBlank(message = "JWT_SECRET must be set")
            @Size(min = 32, message = "JWT_SECRET must be at least 32 characters") String jwtSecret,
            @Min(1) @Max(120) int accessTokenMinutes,
            @Min(1) @Max(90) int refreshTokenDays,
            boolean cookieSecure,
            @NotBlank String cookieSameSite,
            @NotBlank(message = "FRONTEND_ORIGIN must be set") String allowedOrigins,
            @Min(1) int maxFailedLogins,
            @Min(1) int lockoutMinutes,
            /** Server-side lifetime of a session signed in without "remember me" (browser-session cookie). */
            @Min(1) @Max(72) int browserSessionHours) {

        public List<String> allowedOriginList() {
            return Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .map(origin -> origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin)
                    .filter(origin -> !origin.isBlank())
                    .toList();
        }
    }

    /** {@code aiPerMinute} is a per-user budget for SA AI calls (0 disables); it is independent of {@code enabled}. */
    public record RateLimit(boolean enabled, @Min(1) int loginPerMinute, @Min(1) int apiPerMinute, @Min(1) int inboundPerMinute,
                            @Min(0) int aiPerMinute) {}

    public record Redis(boolean enabled) {}

    public record BootstrapAdmin(String email, String password, String fullName) {
        public boolean configured() {
            return email != null && !email.isBlank() && password != null && !password.isBlank();
        }
    }

    public record Demo(boolean seed, String password, boolean showPassword) {}

    public record Ai(
            @NotBlank String provider,
            String apiKey,
            String model,
            String baseUrl,
            @Min(1) @Max(300) int timeoutSeconds,
            @Min(1024) @Max(32000) int maxTokens,
            String effort) {

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Sla(boolean schedulerEnabled, long evaluationIntervalMs) {}

    public record Workflow(int maxDepth, int maxActions, long retryPollIntervalMs, boolean schedulerEnabled, long schedulePollIntervalMs,
                           int scheduledMaxIncidents) {}

    public record Integrations(String encryptionKey, boolean allowPrivateTargets, int timeoutSeconds) {}

    public record Attachments(long maxBytes) {}

    /**
     * Optional SMTP delivery. Without {@code host} (MAIL_HOST) e-mails are recorded in the outbox as SKIPPED and never
     * reported as sent. {@code outboxEnabled} switches the delivery scheduler (tests drive it explicitly).
     */
    public record Mail(String host, @Min(1) @Max(65535) int port, String username, String password, String from, boolean starttls,
                       boolean outboxEnabled, @Min(1000) long pollIntervalMs, @Min(1) @Max(20) int maxAttempts,
                       @Min(1000) @Max(120000) int timeoutMs) {

        public boolean configured() {
            return host != null && !host.isBlank();
        }

        /** Sender address: MAIL_FROM, else MAIL_USERNAME when it is an address, else no-reply at the SMTP host. */
        public String fromAddress() {
            if (from != null && !from.isBlank()) {
                return from.trim();
            }
            if (username != null && username.contains("@")) {
                return username.trim();
            }
            return "no-reply@" + (host == null || host.isBlank() ? "localhost" : host.trim());
        }
    }

    /** Scheduled reports: the scheduler switch, poll interval and how many runs (files) are kept per schedule. */
    public record Reports(boolean schedulerEnabled, @Min(1000) long pollIntervalMs, @Min(1) @Max(1000) int runsKeptPerSchedule) {}
}

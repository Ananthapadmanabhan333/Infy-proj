package com.nexus360.observability;

import com.nexus360.config.AppProperties;
import com.nexus360.integration.UrlSafety;
import com.nexus360.observability.TelemetryAdapter.OutboundRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Outbound HTTP for telemetry adapters: every call is checked by {@link UrlSafety} (https only unless private targets are allowed,
 * no private/loopback/link-local hosts, re-checked before each call because DNS can change), redirects are never followed, the
 * connection and the whole request time out, and response bodies are capped. Credentials are added here from the decrypted secret
 * and are never logged or returned.
 */
@Component
public class OutboundHttp {

    public static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    /** {@code body} is {@code null} when the call failed before a response ({@code error} says why). */
    public record Result(int status, String body, String error, boolean truncated) {
        public boolean ok() {
            return error == null && status >= 200 && status < 300;
        }
    }

    /** Credentials for one call. */
    public record Credentials(String authType, String username, String secret) {
        public static final Credentials NONE = new Credentials("NONE", null, null);
    }

    private final boolean allowPrivateTargets;
    private final Duration timeout;
    private final HttpClient client;

    public OutboundHttp(AppProperties properties) {
        this.allowPrivateTargets = properties.integrations() != null && properties.integrations().allowPrivateTargets();
        int seconds = properties.integrations() == null || properties.integrations().timeoutSeconds() <= 0 ? 10 : properties.integrations().timeoutSeconds();
        this.timeout = Duration.ofSeconds(seconds);
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(Math.min(5, seconds))).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Validates a configured base URL (on save). */
    public URI validate(String url) {
        return UrlSafety.validate(url, allowPrivateTargets);
    }

    public Result execute(OutboundRequest request, Credentials credentials) {
        try {
            UrlSafety.validate(request.uri().toString(), allowPrivateTargets);
        } catch (RuntimeException e) {
            return new Result(0, null, "Blocked by the outbound URL policy: " + e.getMessage(), false);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(request.uri()).timeout(timeout);
        for (Map.Entry<String, String> h : request.headers().entrySet()) {
            b.header(h.getKey(), h.getValue());
        }
        b.header("User-Agent", "SA-Observability/1.0");
        if (credentials != null && credentials.secret() != null && !credentials.secret().isBlank()) {
            switch (credentials.authType() == null ? "NONE" : credentials.authType()) {
                case "BEARER", "API_KEY" -> b.header("Authorization", "Bearer " + credentials.secret());
                case "BASIC" -> b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(((credentials.username() == null ? ""
                        : credentials.username()) + ":" + credentials.secret()).getBytes(StandardCharsets.UTF_8)));
                default -> { }
            }
        }
        if ("POST".equals(request.method())) {
            b.POST(HttpRequest.BodyPublishers.ofString(request.body() == null ? "" : request.body(), StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        try {
            HttpResponse<InputStream> response = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int n;
                boolean truncated = false;
                while ((n = in.read(buffer)) > 0) {
                    if (total + n > MAX_RESPONSE_BYTES) {
                        truncated = true;
                        break;
                    }
                    out.write(buffer, 0, n);
                    total += n;
                }
                if (truncated) {
                    return new Result(response.statusCode(), null, "Response larger than " + (MAX_RESPONSE_BYTES / 1024 / 1024) + " MB", true);
                }
                return new Result(response.statusCode(), out.toString(StandardCharsets.UTF_8), null, false);
            }
        } catch (IOException e) {
            return new Result(0, null, "Connection failed: " + e.getClass().getSimpleName(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(0, null, "Interrupted", false);
        }
    }
}

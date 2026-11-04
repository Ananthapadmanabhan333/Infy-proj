package com.nexus360.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.common.ApiException;
import com.nexus360.config.AppProperties;
import com.nexus360.incident.Incident;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends outbound integration messages. Generic webhooks receive a JSON envelope signed with HMAC-SHA256
 * ({@code X-Nexus-Signature: sha256=<hex>}); Slack and Teams receive a {@code {"text": ...}} message.
 * Redirects are not followed and every delivery is logged.
 */
@Slf4j
@Component
public class IntegrationDispatcher {

    public record Delivery(boolean success, Integer httpStatus, String message, long durationMs) {}

    private final IntegrationRepository integrations;
    private final IntegrationDeliveryRepository deliveries;
    private final SecretCipher cipher;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final HttpClient http;

    public IntegrationDispatcher(IntegrationRepository integrations, IntegrationDeliveryRepository deliveries, SecretCipher cipher,
                                 ObjectMapper mapper, AppProperties properties, TransactionTemplate tx, Clock clock) {
        this.integrations = integrations;
        this.deliveries = deliveries;
        this.cipher = cipher;
        this.mapper = mapper;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Delivery deliver(Long integrationId, String eventType, Map<String, Object> data) {
        record Target(Integration.Type type, String url, String secret, boolean enabled, String name) {}
        Target target = tx.execute(s -> integrations.findById(integrationId)
                .map(i -> new Target(i.getType(), i.getEndpointUrl(), cipher.decrypt(i.getSecretEncrypted()), i.isEnabled(), i.getName()))
                .orElse(null));
        if (target == null) {
            return new Delivery(false, null, "Integration " + integrationId + " does not exist", 0);
        }
        if (!target.enabled() || target.type() == Integration.Type.MONITORING_INBOUND || target.url() == null) {
            return new Delivery(false, null, "Integration '" + target.name() + "' cannot send outbound messages", 0);
        }
        long started = System.nanoTime();
        Delivery result;
        try {
            URI uri = UrlSafety.validate(target.url(), properties.integrations().allowPrivateTargets());
            String body = body(target.type(), eventType, data);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(properties.integrations().timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "NEXUS360-Integrations/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (target.type() == Integration.Type.WEBHOOK) {
                String timestamp = String.valueOf(clock.instant().getEpochSecond());
                request.header("X-Nexus-Event", eventType)
                        .header("X-Nexus-Delivery", UUID.randomUUID().toString())
                        .header("X-Nexus-Timestamp", timestamp);
                if (target.secret() != null) {
                    request.header("X-Nexus-Signature", "sha256=" + hmac(target.secret(), timestamp + "." + body));
                }
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            String snippet = response.body() == null ? "" : response.body();
            result = new Delivery(ok, response.statusCode(), snippet.length() > 300 ? snippet.substring(0, 300) : snippet, elapsed(started));
        } catch (ApiException e) {
            result = new Delivery(false, null, e.getMessage(), elapsed(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = new Delivery(false, null, "Delivery interrupted", elapsed(started));
        } catch (Exception e) {
            result = new Delivery(false, null, e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed(started));
        }
        record(integrationId, eventType, result);
        return result;
    }

    public static Map<String, Object> incidentPayload(Incident i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("number", i.getNumber());
        m.put("title", i.getTitle());
        m.put("priority", i.getPriority().name());
        m.put("status", i.getStatus().name());
        m.put("category", i.getCategory().name());
        m.put("service", i.getService() == null ? null : i.getService().getName());
        m.put("department", i.getDepartment() == null ? null : i.getDepartment().getName());
        m.put("major", i.isMajor());
        m.put("escalationLevel", i.getEscalationLevel());
        return m;
    }

    private String body(Integration.Type type, String eventType, Map<String, Object> data) throws JsonProcessingException {
        if (type == Integration.Type.SLACK || type == Integration.Type.TEAMS) {
            return mapper.writeValueAsString(Map.of("text", text(eventType, data)));
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", eventType);
        envelope.put("occurredAt", clock.instant().toString());
        envelope.put("data", data);
        return mapper.writeValueAsString(envelope);
    }

    @SuppressWarnings("unchecked")
    private static String text(String eventType, Map<String, Object> data) {
        Object incident = data.get("incident");
        if (incident instanceof Map<?, ?> m) {
            Map<String, Object> i = (Map<String, Object>) m;
            return "*SA* · " + eventType + " · " + i.get("number") + " [" + i.get("priority") + "] " + i.get("title")
                    + " — status " + i.get("status") + (i.get("service") == null ? "" : " · " + i.get("service"));
        }
        return "*SA* · " + eventType;
    }

    private void record(Long integrationId, String eventType, Delivery result) {
        try {
            tx.executeWithoutResult(s -> integrations.findById(integrationId).ifPresent(i -> {
                IntegrationDelivery d = new IntegrationDelivery();
                d.setIntegration(i);
                d.setEventType(eventType.length() > 40 ? eventType.substring(0, 40) : eventType);
                d.setStatus(result.success() ? "SUCCESS" : "FAILED");
                d.setHttpStatus(result.httpStatus());
                d.setResponseSnippet(result.message() == null ? null : result.message().length() > 500 ? result.message().substring(0, 500) : result.message());
                d.setDurationMs((int) Math.min(Integer.MAX_VALUE, result.durationMs()));
                deliveries.save(d);
                i.setLastDeliveryAt(clock.instant());
                i.setLastDeliveryStatus(d.getStatus());
                i.setFailureCount(result.success() ? 0 : i.getFailureCount() + 1);
            }));
        } catch (RuntimeException e) {
            log.warn("Could not record delivery for integration {}: {}", integrationId, e.getMessage());
        }
    }

    private static String hmac(String secret, String payload) throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}

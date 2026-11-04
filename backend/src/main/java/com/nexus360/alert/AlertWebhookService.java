package com.nexus360.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.alert.AlertDtos.AlertOutcome;
import com.nexus360.alert.AlertDtos.WebhookResult;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.deployment.DeploymentWebhookService;
import com.nexus360.integration.SecretCipher;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alert webhooks ({@code POST /api/alerts/webhooks/{sourceId}}), one source per monitoring system:
 * <ul>
 *   <li><b>PROMETHEUS</b> (Alertmanager {@code webhook_configs}) and <b>GRAFANA</b> (webhook contact point):
 *       {@code Authorization: Bearer <secret>} or HTTP Basic with the secret as password.</li>
 *   <li><b>GRAFANA</b> alternatively with the contact point's HMAC signature option: {@code X-Grafana-Alerting-Signature} = lowercase hex
 *       HMAC-SHA256(secret, raw body), or HMAC-SHA256(secret, timestamp + ":" + raw body) when {@code X-Grafana-Alerting-Timestamp}
 *       (unix seconds, ± 5 min) is sent. Either the credential or a valid signature is enough.</li>
 *   <li><b>GENERIC</b>: {@code X-Nexus-Timestamp} (unix seconds, ± 5 min), {@code X-Nexus-Delivery} (unique id, replays are answered
 *       DUPLICATE), {@code X-Nexus-Signature: sha256=HMAC-SHA256(secret, timestamp + "." + body)}.</li>
 *   <li><b>CLOUDWATCH</b> (CloudWatch alarm → SNS topic → HTTPS subscription): the SNS signature is verified with the certificate from
 *       {@code https://sns.<region>.amazonaws.com/}, the {@code TopicArn} must be the source's topic, the {@code MessageId} is
 *       processed once; SubscriptionConfirmation messages are confirmed by visiting the (validated) SubscribeURL.</li>
 * </ul>
 * Unknown or inactive sources, missing or wrong credentials, bad signatures, foreign topics and stale timestamps all get the same
 * {@code 401 {"message": "Unauthorized"}} (audited {@code ALERT_WEBHOOK_REJECTED}); malformed payloads {@code 400}.
 */
@Service
@RequiredArgsConstructor
public class AlertWebhookService {

    public static final String UNAUTHORIZED = "Unauthorized";
    private static final long MAX_SKEW_SECONDS = 300;
    static final String GRAFANA_SIGNATURE_HEADER = "X-Grafana-Alerting-Signature";
    static final String GRAFANA_TIMESTAMP_HEADER = "X-Grafana-Alerting-Timestamp";

    private final AlertSourceRepository sources;
    private final AlertIngestService ingest;
    private final SnsMessageVerifier snsVerifier;
    private final SnsGateway snsGateway;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Value("${app.alerts.sns.auto-confirm:true}")
    private boolean autoConfirm = true;

    /** Rejections and malformed payloads keep the delivery log row (FAILED) instead of rolling it back. */
    @Transactional(noRollbackFor = ApiException.class)
    public WebhookResult receive(Long sourceId, byte[] body, Function<String, String> header) {
        byte[] payload = body == null ? new byte[0] : body;
        AlertSource source = sourceId == null ? null : sources.findWithDefaultsById(sourceId).filter(AlertSource::isActive).orElse(null);
        if (source == null) {
            throw reject(sourceId, "unknown or inactive source");
        }
        JsonNode root;
        if (source.getProvider() == AlertSource.Provider.CLOUDWATCH) {
            root = parse(payload, source, null);
            if (root == null || !root.isObject() || !snsVerifier.verify(root) || !source.getSnsTopicArn().equals(text(root, "TopicArn"))) {
                throw reject(sourceId, "invalid SNS signature or topic");
            }
        } else {
            String secret = cipher.decrypt(source.getSecretEncrypted());
            if (secret == null || !authentic(source.getProvider(), secret, payload, header)) {
                throw reject(sourceId, "invalid credentials");
            }
            root = null;
        }
        String eventType = eventType(source, header, root);
        String deliveryId = deliveryId(source, header, root);
        Instant now = clock.instant();
        int inserted = jdbc.update("insert into alert_webhook_deliveries (source_id, delivery_id, event_type, outcome, received_at) values (?, ?, ?, 'IGNORED', ?) "
                + "on conflict (source_id, delivery_id) do nothing", source.getId(), deliveryId, truncate(eventType, 60), Timestamp.from(now));
        if (inserted == 0) {
            return new WebhookResult("DUPLICATE", deliveryId, "This delivery was already received", List.of());
        }
        source.setLastReceivedAt(now);
        if (root == null) {
            root = parse(payload, source, deliveryId);
        }
        Actor actor = new Actor(null, "webhook:" + source.getProvider().name().toLowerCase(Locale.ROOT), source.getName(),
                com.nexus360.user.Role.SYSTEM_ADMIN, 0);
        if (source.getProvider() == AlertSource.Provider.CLOUDWATCH && !"Notification".equals(text(root, "Type"))) {
            return snsControl(source, root, deliveryId, now);
        }
        List<AlertSignal> signals;
        try {
            signals = switch (source.getProvider()) {
                case PROMETHEUS -> AlertPayloads.alertmanager(root);
                case GRAFANA -> AlertPayloads.grafana(root);
                case GENERIC -> AlertPayloads.generic(root);
                case CLOUDWATCH -> {
                    JsonNode alarm = json.readTree(text(root, "Message") == null ? "{}" : text(root, "Message"));
                    AlertSignal signal = AlertPayloads.cloudwatch(alarm);
                    yield signal == null ? List.of() : List.of(signal);
                }
            };
        } catch (IllegalArgumentException | java.io.IOException e) {
            String message = e instanceof IllegalArgumentException ? e.getMessage() : "The SNS Message is not a CloudWatch alarm (JSON expected)";
            record(source.getId(), deliveryId, "FAILED", message, 0);
            throw ApiException.badRequest(message);
        }
        AlertIngestService.Origin origin = new AlertIngestService.Origin(Alert.SourceKind.valueOf(source.getProvider().name()), source, null,
                source.getDefaultService(), source.getDefaultCi(), source.getDefaultSeverity(), actor, source.isDemo(), false, null, null);
        List<AlertOutcome> outcomes = new ArrayList<>();
        for (AlertSignal s : signals) {
            AlertIngestService.Outcome o = ingest.ingest(origin, s);
            if (o.alert() != null) {
                Alert a = o.alert();
                outcomes.add(new AlertOutcome(a.getId(), a.getNumber(), o.action(), a.getStatus(), a.getIncident() == null ? null : a.getIncident().getId(),
                        a.getIncident() == null ? null : a.getIncident().getNumber()));
            } else {
                outcomes.add(new AlertOutcome(null, null, o.action(), null, null, null));
            }
        }
        long recorded = outcomes.stream().filter(o -> o.id() != null).count();
        String outcome = recorded == 0 ? "IGNORED" : "PROCESSED";
        String message = signals.isEmpty() ? "Nothing to record (" + eventType + ")" : recorded + " of " + signals.size() + " alert notification(s) recorded";
        record(source.getId(), deliveryId, outcome, message, (int) recorded);
        audit.recordAs(null, actor.email(), "ALERT_WEBHOOK_RECEIVED", "ALERT_SOURCE", source.getId(), Map.of("source", source.getName(),
                "event", eventType, "delivery", deliveryId, "alerts", recorded), AuditService.SUCCESS);
        return new WebhookResult(outcome, deliveryId, message, outcomes);
    }

    // ------------------------------------------------------------------ SNS control messages

    /** SubscriptionConfirmation (confirmed by visiting the validated SubscribeURL) and UnsubscribeConfirmation messages. */
    private WebhookResult snsControl(AlertSource source, JsonNode root, String deliveryId, Instant now) {
        String type = text(root, "Type");
        String region = SnsGateway.topicRegion(source.getSnsTopicArn());
        if ("SubscriptionConfirmation".equals(type)) {
            String url = text(root, "SubscribeURL");
            if (!SnsGateway.allowedUrl(url, region, false)) {
                record(source.getId(), deliveryId, "FAILED", "SubscribeURL is not an SNS URL of the topic's region", 0);
                throw reject(source.getId(), "foreign SubscribeURL");
            }
            String arn = autoConfirm ? snsGateway.confirm(url.trim()) : null;
            String message;
            if (arn != null) {
                source.setSubscriptionStatus(AlertSource.SubscriptionStatus.CONFIRMED);
                source.setSubscriptionArn(arn.startsWith("arn:") ? truncate(arn, 300) : null);
                source.setSubscriptionConfirmedAt(now);
                message = "Subscription to " + source.getSnsTopicArn() + " confirmed";
            } else {
                source.setSubscriptionStatus(AlertSource.SubscriptionStatus.PENDING);
                message = autoConfirm ? "Subscription confirmation failed; request a new confirmation from the SNS console"
                        : "Subscription pending (automatic confirmation is disabled)";
            }
            record(source.getId(), deliveryId, arn != null ? "PROCESSED" : "FAILED", message, 0);
            audit.recordAs(null, "webhook:cloudwatch", "ALERT_SOURCE_SUBSCRIPTION", "ALERT_SOURCE", source.getId(), Map.of("status",
                    source.getSubscriptionStatus()), AuditService.SUCCESS);
            return new WebhookResult(arn != null ? "PROCESSED" : "IGNORED", deliveryId, message, List.of());
        }
        if ("UnsubscribeConfirmation".equals(type)) {
            source.setSubscriptionStatus(AlertSource.SubscriptionStatus.UNSUBSCRIBED);
            record(source.getId(), deliveryId, "PROCESSED", "The SNS subscription was removed", 0);
            return new WebhookResult("PROCESSED", deliveryId, "The SNS subscription was removed", List.of());
        }
        record(source.getId(), deliveryId, "IGNORED", "Unsupported SNS message type " + type, 0);
        return new WebhookResult("IGNORED", deliveryId, "Unsupported SNS message type", List.of());
    }

    // ------------------------------------------------------------------ authentication

    /** {@code body} is the raw request body exactly as received (the controller binds {@code byte[]}), never a re-serialised one. */
    boolean authentic(AlertSource.Provider provider, String secret, byte[] body, Function<String, String> header) {
        return switch (provider) {
            case PROMETHEUS -> credential(secret, header);
            case GRAFANA -> credential(secret, header) || grafanaSignature(secret, body, header);
            case GENERIC -> {
                String sig = header.apply("X-Nexus-Signature");
                String ts = header.apply("X-Nexus-Timestamp");
                String delivery = header.apply("X-Nexus-Delivery");
                if (sig == null || ts == null || delivery == null || delivery.isBlank() || !sig.startsWith("sha256=") || !ts.matches("^\\d{1,12}$")) {
                    yield false;
                }
                long skew = Math.abs(clock.instant().getEpochSecond() - Long.parseLong(ts));
                yield skew <= MAX_SKEW_SECONDS && signatureMatches(sig.substring(7), secret, prefixed(ts + ".", body));
            }
            case CLOUDWATCH -> false;
        };
    }

    /** {@code Authorization: Bearer <secret>} or HTTP Basic with the secret as password (Alertmanager, Grafana). */
    private static boolean credential(String secret, Function<String, String> header) {
        String auth = header.apply("Authorization");
        if (auth == null) {
            return false;
        }
        String value = auth.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Hashing.constantTimeEquals(value.substring(7).trim(), secret);
        }
        if (value.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(Base64.getDecoder().decode(value.substring(6).trim()), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                return colon >= 0 && Hashing.constantTimeEquals(decoded.substring(colon + 1), secret);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Grafana webhook contact point "HMAC signature" option: {@code X-Grafana-Alerting-Signature} = hex HMAC-SHA256(secret, body); with the optional
     * timestamp header configured, {@code X-Grafana-Alerting-Timestamp} (unix seconds, ± 5 min) and the signed message is {@code timestamp + ":" + body}.
     * A timestamp header that is present but malformed or stale fails the check.
     */
    private boolean grafanaSignature(String secret, byte[] body, Function<String, String> header) {
        String sig = header.apply(GRAFANA_SIGNATURE_HEADER);
        if (sig == null || !sig.trim().matches("^[0-9a-fA-F]{64}$")) {
            return false;
        }
        String ts = header.apply(GRAFANA_TIMESTAMP_HEADER);
        if (ts == null) {
            return signatureMatches(sig.trim(), secret, body);
        }
        String t = ts.trim();
        if (!t.matches("^\\d{1,12}$") || Math.abs(clock.instant().getEpochSecond() - Long.parseLong(t)) > MAX_SKEW_SECONDS) {
            return false;
        }
        return signatureMatches(sig.trim(), secret, prefixed(t + ":", body));
    }

    /** Constant-time comparison of a hex signature (any case) with the lowercase hex HMAC-SHA256 of {@code signed}. */
    private static boolean signatureMatches(String hex, String secret, byte[] signed) {
        return MessageDigest.isEqual(hex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                DeploymentWebhookService.hmacHex(secret, signed).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] prefixed(String prefix, byte[] body) {
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[p.length + body.length];
        System.arraycopy(p, 0, signed, 0, p.length);
        System.arraycopy(body, 0, signed, p.length, body.length);
        return signed;
    }

    private ApiException reject(Long sourceId, String reason) {
        audit.recordAs(null, null, "ALERT_WEBHOOK_REJECTED", "ALERT_SOURCE", sourceId, Map.of("reason", reason), AuditService.DENIED);
        return new ApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED);
    }

    private JsonNode parse(byte[] payload, AlertSource source, String deliveryId) {
        try {
            JsonNode node = json.readTree(payload);
            if (node == null || !node.isObject()) {
                throw new java.io.IOException("not an object");
            }
            return node;
        } catch (java.io.IOException e) {
            if (deliveryId == null) {
                return null;
            }
            record(source.getId(), deliveryId, "FAILED", "Body is not a JSON object", 0);
            throw ApiException.badRequest("The body must be a JSON object");
        }
    }

    private static String eventType(AlertSource source, Function<String, String> header, JsonNode root) {
        return switch (source.getProvider()) {
            case PROMETHEUS -> "alertmanager";
            case GRAFANA -> "grafana";
            case GENERIC -> header.apply("X-Nexus-Event") == null ? "alert" : header.apply("X-Nexus-Event").trim();
            case CLOUDWATCH -> "sns:" + text(root, "Type");
        };
    }

    private static String deliveryId(AlertSource source, Function<String, String> header, JsonNode root) {
        String v = switch (source.getProvider()) {
            case GENERIC -> header.apply("X-Nexus-Delivery");
            case CLOUDWATCH -> text(root, "MessageId");
            default -> null;
        };
        return v == null || v.isBlank() ? source.getProvider().name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID() : truncate(v.trim(), 200);
    }

    private void record(Long sourceId, String deliveryId, String outcome, String message, int count) {
        jdbc.update("update alert_webhook_deliveries set outcome = ?, message = ?, alert_count = ? where source_id = ? and delivery_id = ?", outcome,
                truncate(message, 500), count, sourceId, deliveryId);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

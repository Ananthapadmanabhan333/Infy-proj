package com.nexus360.alert;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Verifies AWS SNS message signatures (SignatureVersion 1 = SHA1withRSA, 2 = SHA256withRSA) as documented by AWS: the canonical
 * string is built from the message fields in byte order ({@code Message, MessageId, Subject (if present), Timestamp, TopicArn, Type}
 * for notifications; {@code Message, MessageId, SubscribeURL, Timestamp, Token, TopicArn, Type} for subscription and unsubscribe
 * confirmations), each as {@code name\nvalue\n}. The signing certificate must come from {@code https://sns.<region>.amazonaws.com/}
 * for the region of the topic ARN.
 */
@Component
@RequiredArgsConstructor
public class SnsMessageVerifier {

    private static final List<String> NOTIFICATION_FIELDS = List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type");
    private static final List<String> SUBSCRIPTION_FIELDS = List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");

    private final SnsGateway gateway;

    /** Whether the message is authentic. Never throws for malformed input (returns false). */
    public boolean verify(JsonNode message) {
        try {
            String type = text(message, "Type");
            String topic = text(message, "TopicArn");
            String region = SnsGateway.topicRegion(topic);
            String certUrl = text(message, "SigningCertURL");
            String signature = text(message, "Signature");
            String version = text(message, "SignatureVersion");
            if (type == null || region == null || signature == null || !SnsGateway.allowedUrl(certUrl, region, true)) {
                return false;
            }
            String algorithm = "1".equals(version) ? "SHA1withRSA" : "2".equals(version) ? "SHA256withRSA" : null;
            if (algorithm == null) {
                return false;
            }
            String canonical = canonical(message, type);
            if (canonical == null) {
                return false;
            }
            PublicKey key = gateway.certificateKey(certUrl.trim());
            if (key == null) {
                return false;
            }
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    /** The canonical string to sign, or {@code null} when a required field is missing or the type is unknown. */
    public static String canonical(JsonNode message, String type) {
        List<String> fields = switch (type) {
            case "Notification" -> NOTIFICATION_FIELDS;
            case "SubscriptionConfirmation", "UnsubscribeConfirmation" -> SUBSCRIPTION_FIELDS;
            default -> null;
        };
        if (fields == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            JsonNode v = message.get(f);
            if (v == null || v.isNull()) {
                if (f.equals("Subject")) {
                    continue;
                }
                return null;
            }
            sb.append(f).append('\n').append(v.asText()).append('\n');
        }
        return sb.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}

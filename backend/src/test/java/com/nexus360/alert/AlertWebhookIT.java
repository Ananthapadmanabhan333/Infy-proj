package com.nexus360.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus360.deployment.DeploymentWebhookService;
import com.nexus360.support.IntegrationTest;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Alert webhooks with real-shaped payloads: Prometheus Alertmanager (bearer and basic auth, grouping by fingerprint, counts, resolve,
 * reopen within the grouping window), Grafana (bearer and the contact point HMAC signature), the generic signed format (HMAC, timestamp, replay protection) and AWS CloudWatch via
 * SNS (subscription confirmation, signature verification with a test certificate, host and topic allow-list, MessageId replay).
 * Every unauthenticated request gets the same generic 401.
 */
class AlertWebhookIT extends IntegrationTest {

    private static final String TOPIC = "arn:aws:sns:ap-south-1:123456789012:sa-test-alarms";
    private static final String CERT_URL = "https://sns.ap-south-1.amazonaws.com/SimpleNotificationService-0000000000000000000000.pem";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SnsGateway gateway;

    private PrivateKey signingKey;
    private String certificatePem;
    private final AtomicInteger confirmations = new AtomicInteger();

    @BeforeEach
    void loadTestCertificate() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/sns/sns-test.p12")) {
            ks.load(in, "test-only-password".toCharArray());
        }
        signingKey = (PrivateKey) ks.getKey("sns-test", "test-only-password".toCharArray());
        Certificate cert = ks.getCertificate("sns-test");
        certificatePem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(cert.getEncoded()) + "\n-----END CERTIFICATE-----\n";
        gateway.useFetcher(this::fakeAws);
    }

    @AfterEach
    void restoreGateway() {
        gateway.useFetcher(null);
    }

    private byte[] fakeAws(URI uri) {
        if (uri.toString().equals(CERT_URL)) {
            return certificatePem.getBytes(StandardCharsets.US_ASCII);
        }
        if (uri.getHost().equals("sns.ap-south-1.amazonaws.com") && uri.getQuery() != null && uri.getQuery().contains("Action=ConfirmSubscription")) {
            confirmations.incrementAndGet();
            return ("<ConfirmSubscriptionResponse xmlns=\"http://sns.amazonaws.com/doc/2010-03-31/\"><ConfirmSubscriptionResult><SubscriptionArn>"
                    + TOPIC + ":2bcfbf39-05c3-41de-beaa-fcfcc21c8f55</SubscriptionArn></ConfirmSubscriptionResult></ConfirmSubscriptionResponse>")
                    .getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("Unexpected request to " + uri);
    }

    private JsonNode source(String provider, String topic) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", unique(provider.toLowerCase() + "-test"), "provider", provider));
        if (topic != null) {
            body.put("topicArn", topic);
        }
        return body(mvc.perform(jsonBody(as("admin", post("/api/alerts/sources")), body)).andExpect(status().isCreated()).andReturn());
    }

    private ResultActions send(long sourceId, String body, Map<String, String> headers) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/alerts/webhooks/" + sourceId).contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        headers.forEach(req::header);
        return mvc.perform(req);
    }

    static String alertmanager(String run, String status, String fp1, String fp2, String status2) {
        return """
                {"receiver":"sa","status":"%s","alerts":[
                  {"status":"%s","labels":{"alertname":"ApiLatencyHigh","instance":"api-%s-01:8080","job":"api","severity":"major","run":"%s"},
                   "annotations":{"summary":"API p99 latency above 2 s on api-%s-01","description":"p99 latency 2.7 s over 10 minutes."},
                   "startsAt":"2026-11-04T08:00:00Z","endsAt":"0001-01-01T00:00:00Z","generatorURL":"http://prometheus:9090/graph?g0.expr=x",
                   "fingerprint":"%s"},
                  {"status":"%s","labels":{"alertname":"ApiLatencyHigh","instance":"api-%s-02:8080","job":"api","severity":"major","run":"%s"},
                   "annotations":{"summary":"API p99 latency above 2 s on api-%s-02"},"startsAt":"2026-11-04T08:01:00Z",
                   "endsAt":"0001-01-01T00:00:00Z","generatorURL":"http://prometheus:9090/graph?g0.expr=x","fingerprint":"%s"}],
                 "groupLabels":{"alertname":"ApiLatencyHigh"},"commonLabels":{"alertname":"ApiLatencyHigh","job":"api"},"commonAnnotations":{},
                 "externalURL":"http://alertmanager:9093","version":"4","groupKey":"{}:{alertname=\\"ApiLatencyHigh\\",run=\\"%s\\"}","truncatedAlerts":0}
                """.formatted(status, status, run, run, run, fp1, status2, run, run, run, fp2, run);
    }

    @Test
    void prometheusAlertsAreAuthenticatedGroupedCountedResolvedAndReopened() throws Exception {
        JsonNode created = source("PROMETHEUS", null);
        long id = created.at("/source/id").asLong();
        String secret = created.get("secret").asText();
        assertThat(secret).startsWith("alsec_");
        assertThat(created.at("/source/webhookPath").asText()).isEqualTo("/api/alerts/webhooks/" + id);
        assertThat(jdbc.queryForObject("select secret_encrypted from alert_sources where id = ?", String.class, id)).startsWith("v1:").doesNotContain(secret);
        String run = UUID.randomUUID().toString().substring(0, 8);
        String fp1 = "fp-" + run + "-1";
        String fp2 = "fp-" + run + "-2";
        String firing = alertmanager(run, "firing", fp1, fp2, "firing");

        // No, wrong or foreign credentials: the same generic 401.
        send(id, firing, Map.of()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Unauthorized"));
        send(id, firing, Map.of("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Unauthorized"));
        send(999_999, firing, Map.of("Authorization", "Bearer " + secret)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized"));
        mvc.perform(post("/api/alerts/webhooks/not-a-number").contentType(MediaType.APPLICATION_JSON).content(firing)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from alerts where fingerprint = ?", Long.class, fp1)).isZero();

        JsonNode first = body(send(id, firing, Map.of("Authorization", "Bearer " + secret)).andExpect(status().isOk()).andReturn());
        assertThat(first.get("outcome").asText()).isEqualTo("PROCESSED");
        assertThat(first.get("alerts").findValuesAsText("action")).containsExactly("CREATED", "CREATED");
        long alertId = first.at("/alerts/0/id").asLong();
        // Alertmanager also supports HTTP Basic auth: the secret is the password.
        String basic = "Basic " + Base64.getEncoder().encodeToString(("alertmanager:" + secret).getBytes(StandardCharsets.UTF_8));
        JsonNode again = body(send(id, firing, Map.of("Authorization", basic)).andExpect(status().isOk()).andReturn());
        assertThat(again.get("alerts").findValuesAsText("action")).containsExactly("UPDATED", "UPDATED");
        assertThat(again.at("/alerts/0/id").asLong()).isEqualTo(alertId);

        JsonNode detail = getJson("arjun.kapoor", "/api/alerts/" + alertId);
        assertThat(detail.at("/alert/occurrenceCount").asInt()).isEqualTo(2);
        assertThat(detail.at("/alert/severity").asText()).isEqualTo("MAJOR");
        assertThat(detail.at("/alert/status").asText()).isEqualTo("OPEN");
        assertThat(detail.at("/alert/sourceKind").asText()).isEqualTo("PROMETHEUS");
        assertThat(detail.at("/alert/groupKey").asText()).isEqualTo("{}:{alertname=\"ApiLatencyHigh\",run=\"" + run + "\"}");
        assertThat(detail.get("fingerprint").asText()).isEqualTo(fp1);
        assertThat(detail.at("/labels/instance").asText()).isEqualTo("api-" + run + "-01:8080");
        assertThat(detail.get("groupAlerts")).hasSize(1);
        assertThat(detail.get("history").findValuesAsText("type")).contains("CREATED", "ROUTED", "REPEATED");
        assertThat(detail.get("allowedActions").toString()).contains("ACKNOWLEDGE", "CONVERT");
        JsonNode groups = getJson("arjun.kapoor", "/api/alerts/groups?limit=100");
        JsonNode group = null;
        for (JsonNode g : groups) {
            if (g.get("groupKey").asText().contains(run)) {
                group = g;
            }
        }
        assertThat(group).isNotNull();
        assertThat(group.get("alertCount").asInt()).isEqualTo(2);
        assertThat(group.get("occurrences").asInt()).isEqualTo(4);
        assertThat(group.get("title").asText()).isEqualTo("ApiLatencyHigh");

        // The first alert resolves; the second keeps firing.
        String partly = alertmanager(run, "firing", fp1, fp2, "firing").replaceFirst("\"status\":\"firing\",\"labels\"", "\"status\":\"resolved\",\"labels\"");
        JsonNode resolved = body(send(id, partly, Map.of("Authorization", "Bearer " + secret)).andExpect(status().isOk()).andReturn());
        assertThat(resolved.get("alerts").findValuesAsText("action")).containsExactly("RESOLVED", "UPDATED");
        detail = getJson("arjun.kapoor", "/api/alerts/" + alertId);
        assertThat(detail.at("/alert/status").asText()).isEqualTo("RESOLVED");
        assertThat(detail.at("/alert/firing").asBoolean()).isFalse();
        assertThat(detail.get("resolutionNote").asText()).isEqualTo("Resolved by the source");

        // Firing again within the grouping window reopens the same alert.
        JsonNode reopened = body(send(id, firing, Map.of("Authorization", "Bearer " + secret)).andExpect(status().isOk()).andReturn());
        assertThat(reopened.at("/alerts/0/action").asText()).isEqualTo("REOPENED");
        assertThat(reopened.at("/alerts/0/id").asLong()).isEqualTo(alertId);
        assertThat(getJson("arjun.kapoor", "/api/alerts/" + alertId).at("/alert/occurrenceCount").asInt()).isEqualTo(4);
        // Outside the window a new alert is created for the same fingerprint.
        send(id, partly, Map.of("Authorization", "Bearer " + secret)).andExpect(status().isOk());
        testClock().advance(java.time.Duration.ofMinutes(45));
        JsonNode fresh = body(send(id, firing, Map.of("Authorization", "Bearer " + secret)).andExpect(status().isOk()).andReturn());
        assertThat(fresh.at("/alerts/0/action").asText()).isEqualTo("CREATED");
        assertThat(fresh.at("/alerts/0/id").asLong()).isNotEqualTo(alertId);
        // A resolved notification for an unknown fingerprint is ignored.
        JsonNode unknown = body(send(id, alertmanager(run + "x", "resolved", "nope-1", "nope-2", "resolved"), Map.of("Authorization", "Bearer " + secret))
                .andExpect(status().isOk()).andReturn());
        assertThat(unknown.get("outcome").asText()).isEqualTo("IGNORED");
        // Malformed payloads are 400 (after authentication).
        send(id, "{\"version\":\"4\"}", Map.of("Authorization", "Bearer " + secret)).andExpect(status().isBadRequest());
        send(id, "not json", Map.of("Authorization", "Bearer " + secret)).andExpect(status().isBadRequest());

        JsonNode deliveries = getJson("admin", "/api/alerts/sources/" + id + "/deliveries");
        assertThat(deliveries.findValuesAsText("outcome")).contains("PROCESSED", "IGNORED", "FAILED");
        mvc.perform(as("riya.varma", get("/api/alerts/sources"))).andExpect(status().isForbidden());
        mvc.perform(as("riya.varma", get("/api/alerts/sources/" + id + "/deliveries"))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ALERT_WEBHOOK_REJECTED' and entity_id = ?", Long.class,
                String.valueOf(id))).isGreaterThanOrEqualTo(2);
    }

    @Test
    void grafanaAndRotatedSecrets() throws Exception {
        JsonNode created = source("GRAFANA", null);
        long id = created.at("/source/id").asLong();
        String secret = created.get("secret").asText();
        String fp = "graf-" + UUID.randomUUID();
        String body = AlertPayloadsTest.GRAFANA.replace("6d0f1e2a3b4c5d6e", fp);
        JsonNode r = body(send(id, body, Map.of("Authorization", "Bearer " + secret)).andExpect(status().isOk()).andReturn());
        assertThat(r.at("/alerts/0/action").asText()).isEqualTo("CREATED");
        JsonNode a = getJson("riya.varma", "/api/alerts/" + r.at("/alerts/0/id").asLong());
        assertThat(a.at("/alert/sourceKind").asText()).isEqualTo("GRAFANA");
        assertThat(a.at("/alert/severity").asText()).isEqualTo("WARNING");
        assertThat(a.get("externalUrl").asText()).contains("viewPanel=4");

        String rotated = body(mvc.perform(as("admin", post("/api/alerts/sources/" + id + "/rotate-secret"))).andExpect(status().isOk()).andReturn())
                .get("secret").asText();
        assertThat(rotated).isNotEqualTo(secret);
        send(id, body, Map.of("Authorization", "Bearer " + secret)).andExpect(status().isUnauthorized());
        send(id, body, Map.of("Authorization", "Bearer " + rotated)).andExpect(status().isOk()).andExpect(jsonPath("$.alerts[0].action").value("UPDATED"));
        // Deactivated sources reject everything.
        mvc.perform(jsonBody(as("admin", org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/alerts/sources/" + id)),
                Map.of("name", created.at("/source/name").asText(), "provider", "GRAFANA", "active", false))).andExpect(status().isOk());
        send(id, body, Map.of("Authorization", "Bearer " + rotated)).andExpect(status().isUnauthorized());
    }

    /** Grafana's webhook contact point "HMAC signature" option (constructed payloads; not verified against a live Grafana). */
    @Test
    void grafanaHmacSignatures() throws Exception {
        JsonNode created = source("GRAFANA", null);
        long id = created.at("/source/id").asLong();
        String secret = created.get("secret").asText();
        assertThat(created.at("/source/authentication").asText()).contains("X-Grafana-Alerting-Signature", "X-Grafana-Alerting-Timestamp");
        String fp = "graf-hmac-" + UUID.randomUUID();
        String body = AlertPayloadsTest.GRAFANA.replace("6d0f1e2a3b4c5d6e", fp);
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        String sig = DeploymentWebhookService.hmacHex(secret, raw);
        long rejected = jdbc.queryForObject("select count(*) from audit_logs where action = 'ALERT_WEBHOOK_REJECTED' and entity_id = ?", Long.class,
                String.valueOf(id));

        // A valid signature without any Authorization header.
        JsonNode r = body(send(id, body, Map.of("X-Grafana-Alerting-Signature", sig)).andExpect(status().isOk()).andReturn());
        assertThat(r.at("/alerts/0/action").asText()).isEqualTo("CREATED");
        assertThat(getJson("riya.varma", "/api/alerts/" + r.at("/alerts/0/id").asLong()).at("/alert/sourceKind").asText()).isEqualTo("GRAFANA");

        // With the timestamp header the signed message is timestamp + ":" + body.
        long ts = clock.instant().getEpochSecond();
        String tsSig = DeploymentWebhookService.hmacHex(secret, (ts + ":" + body).getBytes(StandardCharsets.UTF_8));
        send(id, body, Map.of("X-Grafana-Alerting-Signature", tsSig, "X-Grafana-Alerting-Timestamp", String.valueOf(ts)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alerts[0].action").value("UPDATED"));
        send(id, body, Map.of("X-Grafana-Alerting-Signature", tsSig.toUpperCase(), "X-Grafana-Alerting-Timestamp", String.valueOf(ts)))
                .andExpect(status().isOk());
        // Still within the 5 minute window.
        long early = ts - 240;
        send(id, body, Map.of("X-Grafana-Alerting-Signature", DeploymentWebhookService.hmacHex(secret, (early + ":" + body).getBytes(StandardCharsets.UTF_8)),
                "X-Grafana-Alerting-Timestamp", String.valueOf(early))).andExpect(status().isOk());

        // Stale or future timestamps, a timestamp that was not signed, malformed timestamps.
        for (long t : new long[] {ts - 900, ts + 900}) {
            send(id, body, Map.of("X-Grafana-Alerting-Signature", DeploymentWebhookService.hmacHex(secret, (t + ":" + body).getBytes(StandardCharsets.UTF_8)),
                    "X-Grafana-Alerting-Timestamp", String.valueOf(t))).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Unauthorized"));
        }
        send(id, body, Map.of("X-Grafana-Alerting-Signature", sig, "X-Grafana-Alerting-Timestamp", String.valueOf(ts))).andExpect(status().isUnauthorized());
        send(id, body, Map.of("X-Grafana-Alerting-Signature", tsSig, "X-Grafana-Alerting-Timestamp", "yesterday")).andExpect(status().isUnauthorized());
        // Body changed after signing (also with the timestamp variant).
        send(id, body + " ", Map.of("X-Grafana-Alerting-Signature", sig)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized"));
        send(id, body.replace("vpn-gw-02", "vpn-gw-03"), Map.of("X-Grafana-Alerting-Signature", tsSig, "X-Grafana-Alerting-Timestamp", String.valueOf(ts)))
                .andExpect(status().isUnauthorized());
        // Signed with the wrong secret, or a garbage signature.
        send(id, body, Map.of("X-Grafana-Alerting-Signature", DeploymentWebhookService.hmacHex("wrong-secret", raw))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized"));
        send(id, body, Map.of("X-Grafana-Alerting-Signature", "sha256=" + sig)).andExpect(status().isUnauthorized());
        send(id, body, Map.of("X-Grafana-Alerting-Signature", "")).andExpect(status().isUnauthorized());
        // Neither a credential nor a signature.
        send(id, body, Map.of()).andExpect(status().isUnauthorized());
        send(id, body, Map.of("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ALERT_WEBHOOK_REJECTED' and entity_id = ?", Long.class,
                String.valueOf(id))).isEqualTo(rejected + 11);

        // The bearer credential keeps working on its own; either valid mechanism is enough.
        send(id, body, Map.of("Authorization", "Bearer " + secret)).andExpect(status().isOk());
        send(id, body, Map.of("Authorization", "Bearer wrong", "X-Grafana-Alerting-Signature", sig)).andExpect(status().isOk());
        send(id, body, Map.of("Authorization", "Bearer " + secret, "X-Grafana-Alerting-Signature", DeploymentWebhookService.hmacHex("wrong-secret", raw)))
                .andExpect(status().isOk());
        // Prometheus sources do not accept Grafana signatures.
        JsonNode prom = source("PROMETHEUS", null);
        long promId = prom.at("/source/id").asLong();
        String promBody = alertmanager("hmac", "firing", "fp-hmac-" + UUID.randomUUID(), "fp-hmac-" + UUID.randomUUID(), "firing");
        send(promId, promBody, Map.of("X-Grafana-Alerting-Signature", DeploymentWebhookService.hmacHex(prom.get("secret").asText(),
                promBody.getBytes(StandardCharsets.UTF_8)))).andExpect(status().isUnauthorized());
    }

    @Test
    void genericSignedJsonWithReplayProtection() throws Exception {
        JsonNode created = source("GENERIC", null);
        long id = created.at("/source/id").asLong();
        String secret = created.get("secret").asText();
        String title = unique("Synthetic check failed");
        String body = "{\"title\":\"" + title + "\",\"severity\":\"major\",\"labels\":{\"check\":\"login\"},\"externalUrl\":\"https://status.example.com/x\"}";
        long ts = clock.instant().getEpochSecond();
        String sig = "sha256=" + DeploymentWebhookService.hmacHex(secret, (ts + "." + body).getBytes(StandardCharsets.UTF_8));
        String delivery = UUID.randomUUID().toString();
        send(id, body, Map.of("X-Nexus-Timestamp", String.valueOf(ts), "X-Nexus-Signature", sig)).andExpect(status().isUnauthorized()); // no delivery id
        send(id, body, Map.of("X-Nexus-Timestamp", String.valueOf(ts - 900), "X-Nexus-Delivery", delivery, "X-Nexus-Signature",
                "sha256=" + DeploymentWebhookService.hmacHex(secret, ((ts - 900) + "." + body).getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized()); // stale
        send(id, body + " ", Map.of("X-Nexus-Timestamp", String.valueOf(ts), "X-Nexus-Delivery", delivery, "X-Nexus-Signature", sig))
                .andExpect(status().isUnauthorized()); // body changed after signing
        JsonNode ok = body(send(id, body, Map.of("X-Nexus-Timestamp", String.valueOf(ts), "X-Nexus-Delivery", delivery, "X-Nexus-Signature", sig))
                .andExpect(status().isOk()).andReturn());
        assertThat(ok.get("outcome").asText()).isEqualTo("PROCESSED");
        assertThat(ok.get("deliveryId").asText()).isEqualTo(delivery);
        send(id, body, Map.of("X-Nexus-Timestamp", String.valueOf(ts), "X-Nexus-Delivery", delivery, "X-Nexus-Signature", sig))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        JsonNode a = getJson("riya.varma", "/api/alerts/" + ok.at("/alerts/0/id").asLong());
        assertThat(a.at("/alert/occurrenceCount").asInt()).isEqualTo(1); // the replay was not counted
        assertThat(a.at("/alert/sourceKind").asText()).isEqualTo("GENERIC");
    }

    // ------------------------------------------------------------------ AWS SNS

    private String sns(String type, String messageId, String message, String subject, Map<String, String> extra, String certUrl, String topic,
                       boolean tamper) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("Type", type);
        n.put("MessageId", messageId);
        if (extra.containsKey("Token")) {
            n.put("Token", extra.get("Token"));
        }
        n.put("TopicArn", topic);
        if (subject != null) {
            n.put("Subject", subject);
        }
        n.put("Message", message);
        if (extra.containsKey("SubscribeURL")) {
            n.put("SubscribeURL", extra.get("SubscribeURL"));
        }
        n.put("Timestamp", "2026-11-04T10:03:01.234Z");
        n.put("SignatureVersion", "1");
        Signature s = Signature.getInstance("SHA1withRSA");
        s.initSign(signingKey);
        s.update(SnsMessageVerifier.canonical(n, type).getBytes(StandardCharsets.UTF_8));
        n.put("Signature", Base64.getEncoder().encodeToString(s.sign()));
        n.put("SigningCertURL", certUrl);
        n.put("UnsubscribeURL", "https://sns.ap-south-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=" + topic + ":x");
        if (tamper) {
            n.put("Message", message + " ");
        }
        return json.writeValueAsString(n);
    }

    @Test
    void cloudwatchViaSnsIsVerifiedConfirmedAndDeduplicated() throws Exception {
        JsonNode created = source("CLOUDWATCH", TOPIC);
        long id = created.at("/source/id").asLong();
        assertThat(created.get("secret").isNull()).isTrue();
        assertThat(created.at("/source/subscriptionStatus").asText()).isEqualTo("NONE");
        mvc.perform(jsonBody(as("admin", post("/api/alerts/sources")), Map.of("name", unique("cw-bad"), "provider", "CLOUDWATCH", "topicArn", "not-an-arn")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("topicArn"));
        mvc.perform(as("admin", post("/api/alerts/sources/" + id + "/rotate-secret"))).andExpect(status().isConflict());

        String subscribe = "https://sns.ap-south-1.amazonaws.com/?Action=ConfirmSubscription&TopicArn=" + TOPIC + "&Token=2336412f37fb687f5d51e6e2425c";
        Map<String, String> sub = Map.of("Token", "2336412f37fb687f5d51e6e2425c", "SubscribeURL", subscribe);
        String confirmation = sns("SubscriptionConfirmation", UUID.randomUUID().toString(), "You have chosen to subscribe to the topic " + TOPIC
                + ".\nTo confirm the subscription, visit the SubscribeURL included in this message.", null, sub, CERT_URL, TOPIC, false);
        JsonNode r = body(send(id, confirmation, Map.of("x-amz-sns-message-type", "SubscriptionConfirmation")).andExpect(status().isOk()).andReturn());
        assertThat(r.get("outcome").asText()).isEqualTo("PROCESSED");
        assertThat(confirmations.get()).isEqualTo(1);
        JsonNode src = getJson("admin", "/api/alerts/sources/" + id);
        assertThat(src.get("subscriptionStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(src.get("subscriptionArn").asText()).startsWith(TOPIC + ":");

        String alarmId = UUID.randomUUID().toString();
        String alarm = AlertPayloadsTest.cloudwatchAlarm("ALARM").replace("payroll-alb-5xx", "sa-test-" + alarmId.substring(0, 8));
        // Tampered message, foreign certificate host, wrong topic, unsigned: all the same 401.
        send(id, sns("Notification", alarmId, alarm, "ALARM: test", Map.of(), CERT_URL, TOPIC, true), Map.of()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized"));
        send(id, sns("Notification", alarmId, alarm, "ALARM: test", Map.of(), "https://sns.ap-south-1.amazonaws.com.evil.example/cert.pem", TOPIC, false),
                Map.of()).andExpect(status().isUnauthorized());
        send(id, sns("Notification", alarmId, alarm, "ALARM: test", Map.of(), "http://sns.ap-south-1.amazonaws.com/cert.pem", TOPIC, false), Map.of())
                .andExpect(status().isUnauthorized());
        send(id, sns("Notification", alarmId, alarm, "ALARM: test", Map.of(), CERT_URL.replace("ap-south-1", "us-east-1"),
                TOPIC.replace("ap-south-1", "us-east-1"), false), Map.of()).andExpect(status().isUnauthorized()); // signed, but another topic
        send(id, "{\"Type\":\"Notification\",\"MessageId\":\"x\",\"TopicArn\":\"" + TOPIC + "\",\"Message\":\"{}\"}", Map.of())
                .andExpect(status().isUnauthorized());

        JsonNode fired = body(send(id, sns("Notification", alarmId, alarm, "ALARM: \"sa-test\" in Asia Pacific (Mumbai)", Map.of(), CERT_URL, TOPIC, false),
                Map.of("x-amz-sns-message-type", "Notification")).andExpect(status().isOk()).andReturn());
        assertThat(fired.get("outcome").asText()).isEqualTo("PROCESSED");
        assertThat(fired.get("deliveryId").asText()).isEqualTo(alarmId);
        long alertId = fired.at("/alerts/0/id").asLong();
        JsonNode a = getJson("riya.varma", "/api/alerts/" + alertId);
        assertThat(a.at("/alert/sourceKind").asText()).isEqualTo("CLOUDWATCH");
        assertThat(a.at("/alert/severity").asText()).isEqualTo("CRITICAL");
        assertThat(a.at("/labels/region").asText()).isEqualTo("ap-south-1");
        // Replayed SNS message: acknowledged as duplicate, not counted.
        send(id, sns("Notification", alarmId, alarm, "ALARM: test", Map.of(), CERT_URL, TOPIC, false), Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        // OK state resolves the alert.
        String okMessage = alarm.replace("\"NewStateValue\":\"ALARM\"", "\"NewStateValue\":\"OK\"");
        JsonNode ok = body(send(id, sns("Notification", UUID.randomUUID().toString(), okMessage, "OK: test", Map.of(), CERT_URL, TOPIC, false), Map.of())
                .andExpect(status().isOk()).andReturn());
        assertThat(ok.at("/alerts/0/action").asText()).isEqualTo("RESOLVED");
        assertThat(getJson("riya.varma", "/api/alerts/" + alertId).at("/alert/status").asText()).isEqualTo("RESOLVED");
        // INSUFFICIENT_DATA is acknowledged and ignored.
        String insufficient = alarm.replace("\"NewStateValue\":\"ALARM\"", "\"NewStateValue\":\"INSUFFICIENT_DATA\"");
        send(id, sns("Notification", UUID.randomUUID().toString(), insufficient, null, Map.of(), CERT_URL, TOPIC, false), Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IGNORED"));
        // A confirmation pointing at a non-SNS URL is rejected.
        send(id, sns("SubscriptionConfirmation", UUID.randomUUID().toString(), "confirm", null, Map.of("Token", "t", "SubscribeURL",
                "https://evil.example/?Action=ConfirmSubscription"), CERT_URL, TOPIC, false), Map.of()).andExpect(status().isUnauthorized());
        assertThat(confirmations.get()).isEqualTo(1);
        // Unsubscribe confirmation updates the source.
        send(id, sns("UnsubscribeConfirmation", UUID.randomUUID().toString(), "You have chosen to deactivate subscription", null, sub, CERT_URL, TOPIC,
                false), Map.of()).andExpect(status().isOk());
        assertThat(getJson("admin", "/api/alerts/sources/" + id).get("subscriptionStatus").asText()).isEqualTo("UNSUBSCRIBED");
    }
}

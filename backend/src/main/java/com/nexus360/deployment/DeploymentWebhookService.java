package com.nexus360.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.audit.AuditService;
import com.nexus360.change.ChangeRepository;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.deployment.DeploymentDtos.WebhookResult;
import com.nexus360.integration.SecretCipher;
import com.nexus360.security.Hashing;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CI/CD webhook ingestion. Authentication per provider — GitHub: {@code X-Hub-Signature-256: sha256=HMAC-SHA256(secret,
 * body)}; GitLab: {@code X-Gitlab-Token} equal to the secret; generic: {@code X-Nexus-Signature: sha256=HMAC-SHA256(secret,
 * timestamp + "." + body)} with {@code X-Nexus-Timestamp} (unix seconds, within 5 minutes) — all compared in constant time.
 * Unknown or inactive sources, missing or wrong signatures and stale timestamps get the same {@code 401 Unauthorized}
 * without detail. Deliveries are deduplicated by delivery id (GitHub {@code X-GitHub-Delivery}, GitLab
 * {@code X-Gitlab-Event-UUID}, generic {@code X-Nexus-Delivery}; else the body hash), so replays are acknowledged as
 * DUPLICATE and never processed twice. Supported events: GitHub {@code workflow_run}, {@code deployment_status} (and
 * {@code ping}); GitLab Pipeline Hook and Deployment Hook; generic deployment JSON.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentWebhookService {

    public static final String UNAUTHORIZED = "Unauthorized";
    private static final long MAX_SKEW_SECONDS = 300;
    private static final Pattern CHANGE_NUMBER = Pattern.compile("\\bCHG-\\d{3,}\\b");
    private static final Pattern VERSION = Pattern.compile("\\bv?(\\d+\\.\\d+(?:\\.\\d+)?(?:[-+][0-9A-Za-z.-]+)?)\\b");
    private static final Pattern GITLAB_TIME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}) (UTC|[+-]\\d{4})$");

    /** Normalised event extracted from a provider payload ({@code null} status = not a deployment-relevant event). */
    record Event(String externalId, Deployment.Environment environment, String serviceCode, String version, String commitSha, String pipelineUrl,
                 Deployment.Status status, String actor, Instant startedAt, Instant finishedAt, String changeNumber, String note) {}

    private final DeploymentSourceRepository sources;
    private final DeploymentRepository deployments;
    private final ChangeRepository changes;
    private final BusinessServiceRepository services;
    private final DeploymentService deploymentService;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public WebhookResult receive(Long sourceId, byte[] body, Function<String, String> header) {
        DeploymentSource source = sourceId == null ? null : sources.findWithServiceById(sourceId).filter(DeploymentSource::isActive).orElse(null);
        String secret = source == null ? null : cipher.decrypt(source.getSecretEncrypted());
        byte[] payload = body == null ? new byte[0] : body;
        if (source == null || secret == null || !authentic(source.getProvider(), secret, payload, header)) {
            audit.recordAs(null, null, "DEPLOYMENT_WEBHOOK_REJECTED", "DEPLOYMENT_SOURCE", sourceId, Map.of("reason", source == null ? "unknown source"
                    : "invalid signature"), AuditService.DENIED);
            throw new ApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED);
        }
        String eventType = eventType(source.getProvider(), header);
        String deliveryId = deliveryId(source.getProvider(), header, payload);
        Instant now = clock.instant();
        int inserted = jdbc.update("insert into deployment_webhook_deliveries (source_id, delivery_id, event_type, outcome, received_at) "
                + "values (?, ?, ?, 'IGNORED', ?) on conflict (source_id, delivery_id) do nothing", source.getId(), deliveryId,
                truncate(eventType, 60), Timestamp.from(now));
        if (inserted == 0) {
            return new WebhookResult("DUPLICATE", deliveryId, null, null, "This delivery was already received");
        }
        source.setLastDeliveryAt(now);
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (java.io.IOException e) {
            record(source.getId(), deliveryId, WebhookDelivery.Outcome.FAILED, "Body is not valid JSON", null);
            throw ApiException.badRequest("The body must be JSON");
        }
        Event event;
        try {
            event = switch (source.getProvider()) {
                case GITHUB -> github(eventType, root, source);
                case GITLAB -> gitlab(eventType, root, source);
                case GENERIC -> generic(root, source);
            };
        } catch (IllegalArgumentException e) {
            record(source.getId(), deliveryId, WebhookDelivery.Outcome.FAILED, e.getMessage(), null);
            throw ApiException.badRequest(e.getMessage());
        }
        if (event == null || event.status() == null) {
            String message = "Event " + eventType + " is not a deployment event; nothing recorded";
            record(source.getId(), deliveryId, WebhookDelivery.Outcome.IGNORED, message, null);
            return new WebhookResult("IGNORED", deliveryId, null, null, message);
        }
        Deployment d = apply(source, event, now);
        String message = d.getNumber() + " " + d.getStatus() + " (" + d.getService().getCode() + " " + d.getVersion() + ", " + d.getEnvironment() + ")";
        record(source.getId(), deliveryId, WebhookDelivery.Outcome.PROCESSED, message, d.getId());
        audit.recordAs(null, "webhook:" + source.getProvider().name().toLowerCase(Locale.ROOT), "DEPLOYMENT_WEBHOOK_RECEIVED", "DEPLOYMENT",
                d.getNumber(), Map.of("source", source.getName(), "event", eventType, "delivery", deliveryId, "status", d.getStatus()), AuditService.SUCCESS);
        return new WebhookResult("PROCESSED", deliveryId, d.getId(), d.getNumber(), message);
    }

    /** Creates or updates the deployment of the event (one record per source and external id; terminal states are kept). */
    private Deployment apply(DeploymentSource source, Event e, Instant now) {
        Deployment d = deployments.findByWebhookSourceIdAndExternalId(source.getId(), e.externalId()).orElse(null);
        Deployment.Status before = d == null ? null : d.getStatus();
        if (d == null) {
            d = new Deployment();
            d.setNumber("DEP-" + deployments.nextNumber());
            d.setWebhookSource(source);
            d.setSource(Deployment.Source.valueOf(source.getProvider().name()));
            d.setExternalId(e.externalId());
            d.setDemo(source.isDemo());
            d.setStatus(Deployment.Status.IN_PROGRESS);
        }
        BusinessService service = e.serviceCode() == null ? source.getService()
                : services.findByCodeIgnoreCase(e.serviceCode()).orElse(source.getService());
        d.setService(service);
        d.setEnvironment(e.environment() == null ? source.getDefaultEnvironment() : e.environment());
        d.setVersion(truncate(e.version() == null || e.version().isBlank() ? "unknown" : e.version(), 80));
        if (e.commitSha() != null && e.commitSha().matches("^[0-9a-fA-F]{7,64}$")) {
            d.setCommitSha(e.commitSha().toLowerCase(Locale.ROOT));
        }
        if (e.pipelineUrl() != null && e.pipelineUrl().matches("^https?://.+")) {
            d.setPipelineUrl(truncate(e.pipelineUrl(), 500));
        }
        d.setActorName(truncate(e.actor(), 160));
        if (d.getStartedAt() == null) {
            d.setStartedAt(e.startedAt() == null ? now : e.startedAt());
        }
        if (e.changeNumber() != null && d.getChange() == null) {
            changes.findByNumberIgnoreCase(e.changeNumber()).ifPresent(d::setChange);
        }
        if (e.note() != null) {
            d.setNotes(truncate(e.note(), 2000));
        }
        boolean terminal = before == Deployment.Status.SUCCEEDED || before == Deployment.Status.FAILED || before == Deployment.Status.ROLLED_BACK;
        if (!terminal) {
            d.setStatus(e.status() == Deployment.Status.ROLLED_BACK ? Deployment.Status.FAILED : e.status());
            if (e.status() != Deployment.Status.IN_PROGRESS) {
                Instant finished = e.finishedAt() == null ? now : e.finishedAt();
                d.setFinishedAt(finished.isBefore(d.getStartedAt()) ? d.getStartedAt() : finished);
            }
        }
        deployments.save(d);
        if (before != d.getStatus()) {
            deploymentService.afterStatus(d, before);
        }
        return d;
    }

    // ------------------------------------------------------------------ authentication

    boolean authentic(DeploymentSource.Provider provider, String secret, byte[] body, Function<String, String> header) {
        return switch (provider) {
            case GITHUB -> {
                String sig = header.apply("X-Hub-Signature-256");
                yield sig != null && sig.startsWith("sha256=") && constantTime(sig.substring(7).toLowerCase(Locale.ROOT), hmacHex(secret, body));
            }
            case GITLAB -> {
                String token = header.apply("X-Gitlab-Token");
                yield token != null && Hashing.constantTimeEquals(token, secret);
            }
            case GENERIC -> {
                String sig = header.apply("X-Nexus-Signature");
                String ts = header.apply("X-Nexus-Timestamp");
                if (sig == null || ts == null || !sig.startsWith("sha256=") || !ts.matches("^\\d{1,12}$")) {
                    yield false;
                }
                long skew = Math.abs(clock.instant().getEpochSecond() - Long.parseLong(ts));
                byte[] signed = concat((ts + ".").getBytes(StandardCharsets.UTF_8), body);
                yield skew <= MAX_SKEW_SECONDS && constantTime(sig.substring(7).toLowerCase(Locale.ROOT), hmacHex(secret, signed))
                        && header.apply("X-Nexus-Delivery") != null;
            }
        };
    }

    public static String hmacHex(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTime(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String eventType(DeploymentSource.Provider provider, Function<String, String> header) {
        String v = switch (provider) {
            case GITHUB -> header.apply("X-GitHub-Event");
            case GITLAB -> header.apply("X-Gitlab-Event");
            case GENERIC -> header.apply("X-Nexus-Event");
        };
        return v == null || v.isBlank() ? (provider == DeploymentSource.Provider.GENERIC ? "deployment" : "unknown") : v.trim();
    }

    private static String deliveryId(DeploymentSource.Provider provider, Function<String, String> header, byte[] body) {
        String v = switch (provider) {
            case GITHUB -> header.apply("X-GitHub-Delivery");
            case GITLAB -> Optional.ofNullable(header.apply("X-Gitlab-Event-UUID")).orElse(header.apply("Idempotency-Key"));
            case GENERIC -> header.apply("X-Nexus-Delivery");
        };
        return v == null || v.isBlank() ? "sha256:" + Hashing.sha256Hex(body) : truncate(v.trim(), 200);
    }

    // ------------------------------------------------------------------ payload parsing

    private Event github(String event, JsonNode root, DeploymentSource source) {
        switch (event) {
            case "workflow_run" -> {
                JsonNode run = root.path("workflow_run");
                if (run.isMissingNode() || !run.hasNonNull("id")) {
                    throw new IllegalArgumentException("workflow_run payload without workflow_run.id");
                }
                String status = text(run, "status");
                String conclusion = text(run, "conclusion");
                Deployment.Status s = "completed".equals(status) ? ("success".equals(conclusion) ? Deployment.Status.SUCCEEDED
                        : "skipped".equals(conclusion) || "neutral".equals(conclusion) ? null : Deployment.Status.FAILED) : Deployment.Status.IN_PROGRESS;
                String title = text(run, "display_title");
                String message = text(run.path("head_commit"), "message");
                String sha = text(run, "head_sha");
                return new Event("workflow_run:" + run.get("id").asText(), null, null, version(sha, title, message, text(run, "head_branch")), sha,
                        text(run, "html_url"), s, text(run.path("actor"), "login") == null ? text(root.path("sender"), "login") : text(run.path("actor"), "login"),
                        time(text(run, "run_started_at"), text(run, "created_at")), "completed".equals(status) ? time(text(run, "updated_at"), null) : null,
                        changeNumber(title, message), "GitHub workflow “" + text(run, "name") + "” run #" + text(run, "run_number")
                        + (conclusion == null ? "" : ": " + conclusion));
            }
            case "deployment_status" -> {
                JsonNode st = root.path("deployment_status");
                JsonNode dep = root.path("deployment");
                if (!dep.hasNonNull("id")) {
                    throw new IllegalArgumentException("deployment_status payload without deployment.id");
                }
                String state = text(st, "state");
                Deployment.Status s = switch (state == null ? "" : state) {
                    case "success" -> Deployment.Status.SUCCEEDED;
                    case "failure", "error" -> Deployment.Status.FAILED;
                    case "pending", "queued", "in_progress" -> Deployment.Status.IN_PROGRESS;
                    default -> null;
                };
                String env = text(st, "environment") == null ? text(dep, "environment") : text(st, "environment");
                String sha = text(dep, "sha");
                String description = text(st, "description") == null ? text(dep, "description") : text(st, "description");
                return new Event("deployment:" + dep.get("id").asText(), environment(env), null, version(sha, text(dep, "ref"), description, null), sha,
                        text(st, "target_url") == null ? text(st, "log_url") : text(st, "target_url"), s,
                        text(dep.path("creator"), "login") == null ? text(root.path("sender"), "login") : text(dep.path("creator"), "login"),
                        time(text(dep, "created_at"), null), s == Deployment.Status.IN_PROGRESS ? null : time(text(st, "created_at"), text(st, "updated_at")),
                        changeNumber(description, text(dep, "ref")), "GitHub deployment " + dep.get("id").asText() + ": " + state);
            }
            default -> {
                return null;
            }
        }
    }

    private Event gitlab(String event, JsonNode root, DeploymentSource source) {
        String kind = text(root, "object_kind");
        if ("Pipeline Hook".equals(event) || "pipeline".equals(kind)) {
            JsonNode a = root.path("object_attributes");
            if (!a.hasNonNull("id")) {
                throw new IllegalArgumentException("Pipeline Hook payload without object_attributes.id");
            }
            String status = text(a, "status");
            Deployment.Status s = switch (status == null ? "" : status) {
                case "success" -> Deployment.Status.SUCCEEDED;
                case "failed", "canceled" -> Deployment.Status.FAILED;
                case "created", "waiting_for_resource", "preparing", "pending", "running" -> Deployment.Status.IN_PROGRESS;
                default -> null;
            };
            String sha = text(a, "sha");
            String commitTitle = text(root.path("commit"), "title");
            String commitMessage = text(root.path("commit"), "message");
            return new Event("pipeline:" + a.get("id").asText(), null, null, version(sha, a.path("tag").asBoolean() ? text(a, "ref") : null,
                    commitTitle, commitMessage), sha, text(a, "url"), s, text(root.path("user"), "username"), time(text(a, "created_at"), null),
                    s == Deployment.Status.IN_PROGRESS ? null : time(text(a, "finished_at"), null), changeNumber(commitTitle, commitMessage),
                    "GitLab pipeline " + a.get("id").asText() + ": " + status);
        }
        if ("Deployment Hook".equals(event) || "deployment".equals(kind)) {
            if (!root.hasNonNull("deployment_id")) {
                throw new IllegalArgumentException("Deployment Hook payload without deployment_id");
            }
            String status = text(root, "status");
            Deployment.Status s = switch (status == null ? "" : status) {
                case "success" -> Deployment.Status.SUCCEEDED;
                case "failed", "canceled" -> Deployment.Status.FAILED;
                case "created", "running" -> Deployment.Status.IN_PROGRESS;
                default -> null;
            };
            String title = text(root, "commit_title");
            return new Event("deployment:" + root.get("deployment_id").asText(), environment(text(root, "environment")), null,
                    version(text(root, "short_sha"), text(root, "ref"), title, null), text(root, "short_sha"), text(root, "deployable_url"), s,
                    text(root.path("user"), "username"), null, s == Deployment.Status.IN_PROGRESS ? null : time(text(root, "status_changed_at"), null),
                    changeNumber(title, text(root, "ref")), "GitLab deployment " + root.get("deployment_id").asText() + ": " + status);
        }
        return null;
    }

    private Event generic(JsonNode root, DeploymentSource source) {
        String externalId = text(root, "externalId");
        String version = text(root, "version");
        String status = text(root, "status");
        if (externalId == null || version == null || status == null) {
            throw new IllegalArgumentException("Generic deployment JSON needs externalId, version and status");
        }
        Deployment.Status s;
        try {
            s = Deployment.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be IN_PROGRESS, SUCCEEDED, FAILED or ROLLED_BACK");
        }
        String env = text(root, "environment");
        Deployment.Environment environment = env == null ? null : environment(env);
        if (env != null && environment == null) {
            throw new IllegalArgumentException("environment must be DEV, TEST, STAGING or PRODUCTION");
        }
        return new Event(truncate("generic:" + externalId, 120), environment, text(root, "service"), version, text(root, "commit"),
                text(root, "pipelineUrl"), s, text(root, "actor"), time(text(root, "startedAt"), null), time(text(root, "finishedAt"), null),
                text(root, "changeNumber") == null ? null : text(root, "changeNumber").trim().toUpperCase(Locale.ROOT), text(root, "notes"));
    }

    // ------------------------------------------------------------------ helpers

    static Deployment.Environment environment(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("prod") || n.equals("live")) {
            return Deployment.Environment.PRODUCTION;
        }
        if (n.startsWith("stag") || n.startsWith("preprod") || n.startsWith("uat")) {
            return Deployment.Environment.STAGING;
        }
        if (n.startsWith("test") || n.equals("qa") || n.startsWith("qa-")) {
            return Deployment.Environment.TEST;
        }
        if (n.startsWith("dev") || n.equals("development")) {
            return Deployment.Environment.DEV;
        }
        return null;
    }

    /** Version from a tag/ref, title or message (semantic version), else the short commit id. */
    static String version(String sha, String... texts) {
        for (String t : texts) {
            if (t != null) {
                Matcher m = VERSION.matcher(t);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return sha == null ? null : sha.substring(0, Math.min(7, sha.length()));
    }

    static String changeNumber(String... texts) {
        for (String t : texts) {
            if (t != null) {
                Matcher m = CHANGE_NUMBER.matcher(t);
                if (m.find()) {
                    return m.group();
                }
            }
        }
        return null;
    }

    private static Instant time(String value, String fallback) {
        for (String v : new String[]{value, fallback}) {
            if (v == null || v.isBlank()) {
                continue;
            }
            try {
                return OffsetDateTime.parse(v).toInstant();
            } catch (DateTimeParseException e) {
                Matcher m = GITLAB_TIME.matcher(v.trim());
                if (m.matches()) {
                    String offset = "UTC".equals(m.group(3)) ? "+00:00" : m.group(3).substring(0, 3) + ":" + m.group(3).substring(3);
                    return OffsetDateTime.parse(m.group(1) + "T" + m.group(2) + offset).toInstant();
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private void record(Long sourceId, String deliveryId, WebhookDelivery.Outcome outcome, String message, Long deploymentId) {
        jdbc.update("update deployment_webhook_deliveries set outcome = ?, message = ?, deployment_id = ? where source_id = ? and delivery_id = ?",
                outcome.name(), truncate(message, 500), deploymentId, sourceId, deliveryId);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

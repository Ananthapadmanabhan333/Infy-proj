package com.nexus360.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CI/CD webhooks with real-shaped provider payloads (GitHub workflow_run / deployment_status, GitLab Pipeline / Deployment
 * Hook, generic signed JSON): signature verification, 401 without detail, replay protection, change linking, status
 * updates, and the manual deployment, rollback, calendar, metrics and permission rules.
 */
class DeploymentWebhookIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long service(String code) {
        return jdbc.queryForObject("select id from business_services where code = ?", Long.class, code);
    }

    private JsonNode source(String provider, String serviceCode, String env) throws Exception {
        return body(mvc.perform(jsonBody(as("anil.kumar", post("/api/deployments/sources")), Map.of("name", unique(provider + " pipeline"),
                "provider", provider, "serviceId", service(serviceCode), "defaultEnvironment", env))).andExpect(status().isCreated()).andReturn());
    }

    private ResultActions send(long sourceId, String body, Map<String, String> headers) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/deployments/webhooks/" + sourceId).contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        headers.forEach(req::header);
        return mvc.perform(req);
    }

    private static String sig(String secret, String body) {
        return "sha256=" + DeploymentWebhookService.hmacHex(secret, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String workflowRun(long runId, String action, String status, String conclusion, String sha) {
        return """
                {"action":"%s","workflow_run":{"id":%d,"name":"Build and deploy","node_id":"WFR_kwLOA","head_branch":"main",
                 "head_sha":"%s","path":".github/workflows/deploy.yml","display_title":"Release 3.1.0 for CHG-5001","run_number":562,
                 "event":"push","status":"%s","conclusion":%s,"workflow_id":159038,
                 "html_url":"https://github.com/example-org/payroll/actions/runs/%d","created_at":"2026-11-04T10:00:00Z",
                 "updated_at":"2026-11-04T10:06:30Z","run_started_at":"2026-11-04T10:00:05Z","actor":{"login":"octocat","id":1},
                 "head_commit":{"id":"%s","message":"Release 3.1.0 (CHG-5001)","author":{"name":"Octo Cat"}}},
                 "workflow":{"id":159038,"name":"Build and deploy"},"repository":{"id":1296269,"full_name":"example-org/payroll"},
                 "sender":{"login":"octocat","id":1}}
                """.formatted(action, runId, sha, status, conclusion == null ? "null" : "\"" + conclusion + "\"", runId, sha);
    }

    @Test
    void githubWorkflowRunsAreVerifiedDeduplicatedAndTracked() throws Exception {
        JsonNode created = source("GITHUB", "PAYROLL", "PRODUCTION");
        long id = created.at("/source/id").asLong();
        String secret = created.get("secret").asText();
        assertThat(secret).startsWith("whsec_");
        assertThat(created.at("/source/webhookPath").asText()).isEqualTo("/api/deployments/webhooks/" + id);
        assertThat(jdbc.queryForObject("select secret_encrypted from deployment_sources where id = ?", String.class, id)).startsWith("v1:")
                .doesNotContain(secret);
        long runId = 1_000_000L + (System.nanoTime() % 1_000_000L);
        String sha = "acb5820ced9479c074f688cc328bf03f341a511d";

        String started = workflowRun(runId, "in_progress", "in_progress", null, sha);
        String delivery1 = UUID.randomUUID().toString();
        // Unsigned, wrongly signed and unknown sources get the same 401 without detail.
        send(id, started, Map.of("X-GitHub-Event", "workflow_run", "X-GitHub-Delivery", delivery1)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized"));
        send(id, started, Map.of("X-GitHub-Event", "workflow_run", "X-GitHub-Delivery", delivery1, "X-Hub-Signature-256", sig("wrong", started)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Unauthorized"));
        send(999_999, started, Map.of("X-GitHub-Event", "workflow_run", "X-Hub-Signature-256", sig(secret, started)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Unauthorized"));
        send(id, started + " ", Map.of("X-GitHub-Event", "workflow_run", "X-GitHub-Delivery", delivery1, "X-Hub-Signature-256", sig(secret, started)))
                .andExpect(status().isUnauthorized()); // body changed after signing

        JsonNode first = body(send(id, started, Map.of("X-GitHub-Event", "workflow_run", "X-GitHub-Delivery", delivery1,
                "X-Hub-Signature-256", sig(secret, started))).andExpect(status().isOk()).andReturn());
        assertThat(first.get("outcome").asText()).isEqualTo("PROCESSED");
        long deploymentId = first.get("deploymentId").asLong();
        JsonNode d = getJson("riya.varma", "/api/deployments/" + deploymentId);
        assertThat(d.at("/deployment/status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(d.at("/deployment/version").asText()).isEqualTo("3.1.0");
        assertThat(d.at("/deployment/environment").asText()).isEqualTo("PRODUCTION");
        assertThat(d.at("/deployment/source").asText()).isEqualTo("GITHUB");
        assertThat(d.at("/deployment/changeNumber").asText()).isEqualTo("CHG-5001"); // linked from the commit message
        assertThat(d.at("/deployment/pipelineUrl").asText()).endsWith("/actions/runs/" + runId);

        // Replay of the same delivery: acknowledged, not processed again.
        send(id, started, Map.of("X-GitHub-Event", "workflow_run", "X-GitHub-Delivery", delivery1, "X-Hub-Signature-256", sig(secret, started)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        String done = workflowRun(runId, "completed", "completed", "success", sha);
        send(id, done, Map.of("X-GitHub-Event", "workflow_run", "X-GitHub-Delivery", UUID.randomUUID().toString(), "X-Hub-Signature-256", sig(secret, done)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deploymentId").value(deploymentId));
        d = getJson("riya.varma", "/api/deployments/" + deploymentId);
        assertThat(d.at("/deployment/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(d.at("/deployment/finishedAt").asText()).isEqualTo("2026-11-04T10:06:30Z");
        assertThat(d.at("/risk/level").asText()).isIn("LOW", "MEDIUM", "HIGH");
        // ping is acknowledged and ignored.
        String ping = "{\"zen\":\"Keep it logically awesome.\",\"hook_id\":12345678}";
        send(id, ping, Map.of("X-GitHub-Event", "ping", "X-GitHub-Delivery", UUID.randomUUID().toString(), "X-Hub-Signature-256", sig(secret, ping)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IGNORED"));
        JsonNode deliveries = getJson("anil.kumar", "/api/deployments/sources/" + id + "/deliveries");
        assertThat(deliveries.findValuesAsText("outcome")).contains("PROCESSED", "IGNORED");
        assertThat(deliveries).hasSize(3);
        mvc.perform(as("riya.varma", get("/api/deployments/sources"))).andExpect(status().isForbidden());
    }

    @Test
    void githubDeploymentStatusFailuresAreAnnounced() throws Exception {
        JsonNode created = source("GITHUB", "HR-PORTAL", "STAGING");
        long id = created.at("/source/id").asLong();
        String secret = created.get("secret").asText();
        String body = """
                {"action":"created","deployment_status":{"url":"https://api.github.com/repos/example-org/hr-portal/deployments/42/statuses/1",
                 "id":1,"state":"failure","environment":"production","target_url":"https://github.com/example-org/hr-portal/actions/runs/7",
                 "description":"Health check failed","created_at":"2026-11-04T11:00:00Z","updated_at":"2026-11-04T11:00:00Z"},
                 "deployment":{"id":%d,"sha":"9f1c2b3a4d5e6f708192a3b4c5d6e7f8091a2b3c","ref":"v5.2.0","task":"deploy","environment":"production",
                 "description":"Deploy v5.2.0","creator":{"login":"deploy-bot"},"created_at":"2026-11-04T10:55:00Z","payload":{}},
                 "repository":{"full_name":"example-org/hr-portal"},"sender":{"login":"deploy-bot"}}
                """.formatted(System.nanoTime() % 1_000_000L);
        JsonNode r = body(send(id, body, Map.of("X-GitHub-Event", "deployment_status", "X-GitHub-Delivery", UUID.randomUUID().toString(),
                "X-Hub-Signature-256", sig(secret, body))).andExpect(status().isOk()).andReturn());
        JsonNode d = getJson("anil.kumar", "/api/deployments/" + r.get("deploymentId").asLong()).get("deployment");
        assertThat(d.get("status").asText()).isEqualTo("FAILED");
        assertThat(d.get("environment").asText()).isEqualTo("PRODUCTION"); // from the payload, not the source default
        assertThat(d.get("version").asText()).isEqualTo("5.2.0");
        assertThat(getJson("anil.kumar", "/api/notifications?size=30").at("/content").findValuesAsText("type")).contains("DEPLOYMENT_FAILED");
    }

    @Test
    void gitlabPipelineAndDeploymentHooksUseTheSecretToken() throws Exception {
        JsonNode created = source("GITLAB", "ERP", "STAGING");
        long id = created.at("/source/id").asLong();
        String token = created.get("secret").asText();
        long pipeline = System.nanoTime() % 1_000_000L;
        String body = """
                {"object_kind":"pipeline","object_attributes":{"id":%d,"iid":3,"name":null,"ref":"main","tag":false,
                 "sha":"bcbb5ec396a2c0f828686f14fac9b80b780504f2","before_sha":"bcbb5ec396a2c0f828686f14fac9b80b780504f2","source":"push",
                 "status":"success","detailed_status":"passed","stages":["build","test","deploy"],"created_at":"2026-11-04 10:00:00 UTC",
                 "finished_at":"2026-11-04 10:12:00 UTC","duration":720,"url":"https://gitlab.example.com/it/erp/-/pipelines/%d"},
                 "user":{"id":1,"name":"Administrator","username":"root"},"project":{"id":1,"name":"erp","path_with_namespace":"it/erp",
                 "web_url":"https://gitlab.example.com/it/erp"},"commit":{"id":"bcbb5ec396a2c0f828686f14fac9b80b780504f2",
                 "message":"Release 7.3.1\\n\\nCHG-5002","title":"Release 7.3.1","timestamp":"2026-11-04T09:58:00+00:00"},"builds":[]}
                """.formatted(pipeline, pipeline);
        String uuid = UUID.randomUUID().toString();
        send(id, body, Map.of("X-Gitlab-Event", "Pipeline Hook", "X-Gitlab-Event-UUID", uuid, "X-Gitlab-Token", "not-the-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Unauthorized"));
        send(id, body, Map.of("X-Gitlab-Event", "Pipeline Hook", "X-Gitlab-Event-UUID", uuid)).andExpect(status().isUnauthorized());
        JsonNode r = body(send(id, body, Map.of("X-Gitlab-Event", "Pipeline Hook", "X-Gitlab-Event-UUID", uuid, "X-Gitlab-Token", token))
                .andExpect(status().isOk()).andReturn());
        assertThat(r.get("outcome").asText()).isEqualTo("PROCESSED");
        JsonNode d = getJson("anil.kumar", "/api/deployments/" + r.get("deploymentId").asLong()).get("deployment");
        assertThat(d.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(d.get("environment").asText()).isEqualTo("STAGING");
        assertThat(d.get("version").asText()).isEqualTo("7.3.1");
        assertThat(d.get("changeNumber").asText()).isEqualTo("CHG-5002");
        assertThat(d.get("startedAt").asText()).isEqualTo("2026-11-04T10:00:00Z");
        send(id, body, Map.of("X-Gitlab-Event", "Pipeline Hook", "X-Gitlab-Event-UUID", uuid, "X-Gitlab-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        String deploy = """
                {"object_kind":"deployment","status":"running","status_changed_at":"2026-11-04 10:20:00 +0000","deployment_id":%d,
                 "deployable_id":796,"deployable_url":"https://gitlab.example.com/it/erp/-/jobs/796","environment":"production",
                 "project":{"id":1,"name":"erp","path_with_namespace":"it/erp"},"short_sha":"279484c0","user":{"username":"root"},
                 "commit_url":"https://gitlab.example.com/it/erp/-/commit/279484c0","commit_title":"Release 7.3.1","ref":"v7.3.1"}
                """.formatted(pipeline);
        JsonNode dep = body(send(id, deploy, Map.of("X-Gitlab-Event", "Deployment Hook", "X-Gitlab-Token", token)).andExpect(status().isOk()).andReturn());
        JsonNode running = getJson("anil.kumar", "/api/deployments/" + dep.get("deploymentId").asLong()).get("deployment");
        assertThat(running.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(running.get("environment").asText()).isEqualTo("PRODUCTION");
        // Without a delivery header the body hash is the delivery id: an identical repeat is a replay.
        send(id, deploy, Map.of("X-Gitlab-Event", "Deployment Hook", "X-Gitlab-Token", token)).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
    }

    @Test
    void genericSignedJsonNeedsAFreshTimestampAndADeliveryId() throws Exception {
        JsonNode created = source("GENERIC", "VPN", "TEST");
        long id = created.at("/source/id").asLong();
        String secret = created.get("secret").asText();
        String body = "{\"externalId\":\"build-" + UUID.randomUUID() + "\",\"service\":\"VPN\",\"environment\":\"PRODUCTION\",\"version\":\"9.20\","
                + "\"commit\":\"0123456789abcdef\",\"pipelineUrl\":\"https://ci.example.com/job/981\",\"status\":\"FAILED\",\"actor\":\"jenkins\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String delivery = UUID.randomUUID().toString();
        String good = "sha256=" + DeploymentWebhookService.hmacHex(secret, (ts + "." + body).getBytes(StandardCharsets.UTF_8));
        send(id, body, Map.of("X-Nexus-Timestamp", ts, "X-Nexus-Signature", good)).andExpect(status().isUnauthorized()); // no delivery id
        String old = String.valueOf(Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond());
        send(id, body, Map.of("X-Nexus-Timestamp", old, "X-Nexus-Delivery", delivery, "X-Nexus-Signature",
                "sha256=" + DeploymentWebhookService.hmacHex(secret, (old + "." + body).getBytes(StandardCharsets.UTF_8)))).andExpect(status().isUnauthorized());
        send(id, body, Map.of("X-Nexus-Timestamp", ts, "X-Nexus-Delivery", delivery, "X-Nexus-Signature", sig(secret, body)))
                .andExpect(status().isUnauthorized()); // signature without the timestamp
        JsonNode r = body(send(id, body, Map.of("X-Nexus-Timestamp", ts, "X-Nexus-Delivery", delivery, "X-Nexus-Signature", good))
                .andExpect(status().isOk()).andReturn());
        JsonNode d = getJson("anil.kumar", "/api/deployments/" + r.get("deploymentId").asLong()).get("deployment");
        assertThat(d.get("status").asText()).isEqualTo("FAILED");
        assertThat(d.get("environment").asText()).isEqualTo("PRODUCTION");
        assertThat(d.get("commitSha").asText()).isEqualTo("0123456789abcdef");
        send(id, body, Map.of("X-Nexus-Timestamp", ts, "X-Nexus-Delivery", delivery, "X-Nexus-Signature", good))
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        String invalid = "{\"externalId\":\"x\",\"version\":\"1\",\"status\":\"EXPLODED\"}";
        String sig2 = "sha256=" + DeploymentWebhookService.hmacHex(secret, (ts + "." + invalid).getBytes(StandardCharsets.UTF_8));
        send(id, invalid, Map.of("X-Nexus-Timestamp", ts, "X-Nexus-Delivery", UUID.randomUUID().toString(), "X-Nexus-Signature", sig2))
                .andExpect(status().isBadRequest());
        // Rotating the secret invalidates the old one.
        String rotated = body(mvc.perform(as("anil.kumar", post("/api/deployments/sources/" + id + "/rotate-secret"))).andExpect(status().isOk())
                .andReturn()).get("secret").asText();
        assertThat(rotated).isNotEqualTo(secret);
        send(id, body, Map.of("X-Nexus-Timestamp", ts, "X-Nexus-Delivery", UUID.randomUUID().toString(), "X-Nexus-Signature", good))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void manualDeploymentsRollBackAndFeedTheCalendarAndMetrics() throws Exception {
        long svc = service("EUC");
        mvc.perform(jsonBody(as("riya.varma", post("/api/deployments")), Map.of("serviceId", svc, "environment", "PRODUCTION", "version", "1.0")))
                .andExpect(status().isForbidden());
        JsonNode v1 = body(mvc.perform(jsonBody(as("anil.kumar", post("/api/deployments")), Map.of("serviceId", svc, "environment", "PRODUCTION",
                "version", "4.0.0", "status", "SUCCEEDED", "startedAt", Instant.now().minus(Duration.ofHours(5)).toString()))).andExpect(status().isCreated()).andReturn());
        JsonNode v2 = body(mvc.perform(jsonBody(as("anil.kumar", post("/api/deployments")), Map.of("serviceId", svc, "environment", "PRODUCTION",
                "version", "4.1.0", "commitSha", "deadbeef00"))).andExpect(status().isCreated()).andReturn());
        assertThat(v2.get("status").asText()).isEqualTo("IN_PROGRESS");
        long id = v2.get("id").asLong();
        mvc.perform(jsonBody(as("anil.kumar", post("/api/deployments/" + id + "/rollback")), Map.of("reason", "Too early"))).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("anil.kumar", post("/api/deployments/" + id + "/status")), Map.of("status", "FAILED", "notes", "Smoke test failed")))
                .andExpect(status().isOk());
        mvc.perform(jsonBody(as("anil.kumar", post("/api/deployments/" + id + "/status")), Map.of("status", "SUCCEEDED"))).andExpect(status().isConflict());
        JsonNode back = body(mvc.perform(jsonBody(as("anil.kumar", post("/api/deployments/" + id + "/rollback")), Map.of("reason", "Smoke test failed")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(back.get("version").asText()).isEqualTo(v1.get("version").asText()); // previous successful version
        assertThat(back.get("rollbackOfNumber").asText()).isEqualTo(v2.get("number").asText());
        JsonNode original = getJson("anil.kumar", "/api/deployments/" + id);
        assertThat(original.at("/deployment/status").asText()).isEqualTo("ROLLED_BACK");
        assertThat(original.get("rollbacks").findValuesAsText("number")).contains(back.get("number").asText());
        assertThat(original.at("/risk/factors").findValuesAsText("key")).contains("production", "noChange");

        JsonNode calendar = getJson("ananya.bose", "/api/deployments/calendar?serviceId=" + svc);
        assertThat(calendar.get("days").findValuesAsText("number")).contains(v1.get("number").asText(), v2.get("number").asText());
        JsonNode metrics = getJson("ananya.bose", "/api/deployments/metrics?days=30");
        assertThat(metrics.at("/byStatus/ROLLED_BACK").asLong()).isPositive();
        assertThat(metrics.get("productionWithoutChange").asLong()).isPositive();
        assertThat(getJson("riya.varma", "/api/deployments?serviceId=" + svc + "&environment=PRODUCTION&sort=startedAt,asc").get("content")
                .findValuesAsText("version")).contains("4.0.0", "4.1.0");
        mvc.perform(as("kavya.reddy", get("/api/deployments"))).andExpect(status().isForbidden());

        JsonNode release = body(mvc.perform(jsonBody(as("anil.kumar", post("/api/releases")), Map.of("name", unique("Desktop image"), "version", "4.2",
                "serviceId", svc))).andExpect(status().isCreated()).andReturn());
        assertThat(release.get("number").asText()).startsWith("REL-");
        assertThat(getJson("riya.varma", "/api/releases?status=PLANNED&size=100").get("content").findValuesAsText("id"))
                .contains(release.get("id").asText());
        // Demo data: the failed and rolled-back VPN firmware deployment.
        assertThat(getJson("riya.varma", "/api/deployments?serviceId=" + service("VPN") + "&status=ROLLED_BACK").get("totalElements").asLong()).isPositive();
    }
}

package com.nexus360.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import com.nexus360.user.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Release 4 roles (SECURITY_ANALYST, FINOPS_ANALYST) and the permission gates of every new endpoint: employees are denied everything
 * except the simulation status (global banner), auditors and the analysts read but never write.
 */
class Release4RolesIT extends IntegrationTest {

    static final List<String> READS = List.of("/api/observability/overview", "/api/observability/sources", "/api/observability/sources/kinds",
            "/api/observability/mappings", "/api/observability/resources", "/api/observability/metrics", "/api/observability/metrics/series?name=x",
            "/api/observability/logs", "/api/observability/traces", "/api/observability/services/red", "/api/observability/retention",
            "/api/observability/anomaly-detectors", "/api/observability/anomalies", "/api/observability/correlation-groups", "/api/twin/meta", "/api/twin/graph",
            "/api/twin/simulations", "/api/twin/changes", "/api/simulation/runs");

    /** Writes with a body that would be valid enough to pass validation, so a 403 can only come from the permission check. */
    static List<MockHttpServletRequestBuilder> writes() {
        return List.of(post("/api/observability/sources"), put("/api/observability/sources/1"), delete("/api/observability/sources/1"),
                post("/api/observability/sources/1/rotate-token"), post("/api/observability/sources/1/test"), post("/api/observability/sources/1/pull"),
                post("/api/observability/mappings"), put("/api/observability/mappings/1"), delete("/api/observability/mappings/1"),
                post("/api/observability/mappings/reapply"), put("/api/observability/retention"), post("/api/observability/retention/purge"),
                post("/api/observability/anomaly-detectors"), put("/api/observability/anomaly-detectors/1"), delete("/api/observability/anomaly-detectors/1"),
                post("/api/observability/anomaly-detectors/1/run"), post("/api/observability/anomaly-detectors/1/evaluate"),
                post("/api/observability/correlation-groups/1/link"), post("/api/observability/correlation-groups/1/convert"),
                post("/api/observability/correlation-groups/1/split"), post("/api/observability/correlation-groups/1/merge"),
                post("/api/observability/correlation-groups/1/dismiss"), put("/api/twin/relationships/1"), put("/api/simulation/mode"),
                post("/api/simulation/scenarios/BASELINE/start"), post("/api/simulation/scenarios/BASELINE/stop"), delete("/api/simulation/data"));
    }

    private int status(String user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(as(user, request).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
    }

    @Test
    void newRolesExistAsDemoUsersAndReadOperationalData() throws Exception {
        assertThat(Role.READERS).contains(Role.SECURITY_ANALYST, Role.FINOPS_ANALYST);
        assertThat(Role.STAFF).doesNotContain(Role.SECURITY_ANALYST, Role.FINOPS_ANALYST);
        JsonNode accounts = body(mvc.perform(get("/api/auth/demo-accounts")).andReturn());
        assertThat(accounts.toString()).contains("vikram.sec@demo.nexus360.io").contains("SECURITY_ANALYST").contains("neha.finops@demo.nexus360.io")
                .contains("FINOPS_ANALYST");
        for (String user : List.of("vikram.sec", "neha.finops")) {
            JsonNode me = getJson(user, "/api/auth/me");
            assertThat(me.toString()).contains(user.equals("vikram.sec") ? "SECURITY_ANALYST" : "FINOPS_ANALYST");
            for (String path : READS) {
                assertThat(status(user, get(path))).as(user + " GET " + path).isEqualTo(200);
            }
            // Existing READER modules open up; staff-only actions stay closed.
            assertThat(status(user, get("/api/alerts"))).isEqualTo(200);
            assertThat(status(user, post("/api/alerts"))).isEqualTo(403);
        }
    }

    @Test
    void employeesAreDeniedEveryNewEndpointExceptTheSimulationStatus() throws Exception {
        for (String path : READS) {
            assertThat(status("rohan.kulkarni", get(path))).as("employee GET " + path).isEqualTo(403);
        }
        for (MockHttpServletRequestBuilder w : writes()) {
            assertThat(status("rohan.kulkarni", w)).as("employee write").isEqualTo(403);
        }
        assertThat(status("rohan.kulkarni", post("/api/twin/simulations"))).isEqualTo(403);
        assertThat(status("rohan.kulkarni", get("/api/simulation/status"))).isEqualTo(200);
        assertThat(mvc.perform(get("/api/simulation/status")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void auditorsAndAnalystsReadButNeverWrite() throws Exception {
        for (String user : List.of("ananya.bose", "vikram.sec", "neha.finops")) {
            for (String path : READS) {
                assertThat(status(user, get(path))).as(user + " GET " + path).isEqualTo(200);
            }
            for (MockHttpServletRequestBuilder w : writes()) {
                assertThat(status(user, w)).as(user + " write").isEqualTo(403);
            }
        }
        // Configuration writes are for TELEMETRY_CONFIG only: managers and agents are denied too.
        assertThat(status("priya.raman", post("/api/observability/sources"))).isEqualTo(403);
        assertThat(status("riya.varma", post("/api/observability/anomaly-detectors"))).isEqualTo(403);
        // IT administrators configure telemetry but do not control simulation mode.
        assertThat(status("rajesh.iyer", put("/api/simulation/mode"))).isEqualTo(403);
        assertThat(status("rajesh.iyer", post("/api/observability/sources"))).isEqualTo(400);
    }

    @Test
    void roleGroupsMatchTheSpecification() {
        assertThat(Roles.TWIN_EDITOR_ROLES).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "ASSET_MANAGER", "CHANGE_MANAGER");
        assertThat(Roles.SECOPS_READER_ROLES).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "SECURITY_ANALYST", "INCIDENT_MANAGER", "AUDITOR",
                "IT_MANAGER");
        assertThat(Roles.FINOPS_READER_ROLES).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "FINOPS_ANALYST", "IT_MANAGER", "AUDITOR",
                "CHANGE_MANAGER", "ASSET_MANAGER");
        assertThat(Roles.AGENT_USER_ROLES).contains("SECURITY_ANALYST").doesNotContain("FINOPS_ANALYST", "AUDITOR", "EMPLOYEE");
        assertThat(Roles.SIMULATION_ADMIN_ROLES).containsExactly("SYSTEM_ADMIN");
        assertThat(Roles.READER_ROLES).contains("SECURITY_ANALYST", "FINOPS_ANALYST");
        assertThat(Roles.READERS).contains("'SECURITY_ANALYST'").contains("'FINOPS_ANALYST'");
        assertThat(Map.of("x", Roles.OBSERVABILITY_READERS)).containsValue(Roles.READERS);
    }
}

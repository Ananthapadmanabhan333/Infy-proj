package com.nexus360.cmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CMDB: business-service CIs and their dependency relationships follow the services, CI CRUD with validation, history and permissions,
 * typed relationships, cycle-safe impact analysis (upstream/downstream, depth limit, impacted services and open incidents), the graph
 * endpoint, the demo payroll chain, and CI links on problems and changes (explicit ids and by-name matching).
 */
class CmdbIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long ciId(String name) {
        return jdbc.queryForObject("select id from configuration_items where lower(name) = lower(?)", Long.class, name);
    }

    private long dept(String code) {
        return jdbc.queryForObject("select id from departments where code = ?", Long.class, code);
    }

    private long serviceId(String code) {
        return jdbc.queryForObject("select id from business_services where code = ?", Long.class, code);
    }

    private JsonNode createCi(String user, Map<String, Object> body) throws Exception {
        return body(mvc.perform(jsonBody(as(user, post("/api/cmdb/cis")), body)).andExpect(status().isCreated()).andReturn());
    }

    private long relate(long source, long target, String type) throws Exception {
        return body(mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/relationships")), Map.of("sourceId", source, "targetId", target, "type", type)))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private static List<String> names(JsonNode nodes) {
        List<String> out = new ArrayList<>();
        nodes.forEach(n -> out.add(n.at("/ci/name").asText()));
        return out;
    }

    @Test
    void demoPayrollChainAndImpactAnalysis() throws Exception {
        long san = ciId("san-array-01");
        JsonNode down = getJson("riya.varma", "/api/cmdb/cis/" + san + "/impact?direction=downstream&depth=5");
        assertThat(names(down.get("downstream"))).contains("payroll-db-primary", "payroll-db-replica", "erp-db-01", "Payroll Application", "Payroll",
                "ERP Finance", "Database Platform");
        assertThat(down.get("upstream")).isEmpty();
        assertThat(down.get("impactedServices").findValuesAsText("code")).contains("PAYROLL", "DATABASE", "ERP");
        assertThat(down.get("truncated").asBoolean()).isFalse();
        JsonNode direct = getJson("riya.varma", "/api/cmdb/cis/" + san + "/impact?direction=downstream&depth=1");
        assertThat(names(direct.get("downstream"))).containsExactlyInAnyOrder("payroll-db-primary", "payroll-db-replica", "erp-db-01");
        assertThat(direct.get("downstream").findValuesAsText("via")).containsOnly("DEPENDS_ON");

        long app = ciId("Payroll Application");
        JsonNode up = getJson("ananya.bose", "/api/cmdb/cis/" + app + "/impact?direction=upstream");
        assertThat(names(up.get("upstream"))).contains("payroll-app-01", "payroll-app-02", "payroll-db-primary", "san-array-01", "Identity Provider (ADFS)",
                "adfs-01");
        // The demo payroll alerts are open on the payroll CIs.
        JsonNode both = getJson("riya.varma", "/api/cmdb/cis/" + ciId("payroll-db-primary") + "/impact");
        assertThat(both.get("direction").asText()).isEqualTo("both");
        assertThat(both.get("openAlerts").findValuesAsText("ciName")).contains("payroll-db-primary");
        assertThat(both.get("impactedServices").findValuesAsText("code")).contains("PAYROLL");
        mvc.perform(as("riya.varma", get("/api/cmdb/cis/" + san + "/impact?depth=11"))).andExpect(status().isBadRequest());
        mvc.perform(as("riya.varma", get("/api/cmdb/cis/" + san + "/impact?direction=sideways"))).andExpect(status().isBadRequest());
        mvc.perform(as("kavya.reddy", get("/api/cmdb/cis/" + san + "/impact"))).andExpect(status().isForbidden());

        // VPN gateways and the identity service.
        JsonNode adfs = getJson("riya.varma", "/api/cmdb/cis/" + ciId("Identity Provider (ADFS)") + "/impact?direction=downstream");
        assertThat(names(adfs.get("downstream"))).contains("vpn-gw-01", "vpn-gw-02", "VPN & Remote Access", "Identity & Access (SSO)", "HR Portal");

        // Graph around the payroll application.
        JsonNode graph = getJson("riya.varma", "/api/cmdb/graph?ciId=" + app + "&depth=2");
        assertThat(graph.get("rootId").asLong()).isEqualTo(app);
        assertThat(graph.get("nodes").findValuesAsText("name")).contains("Payroll Application", "payroll-app-01", "payroll-db-primary", "Payroll",
                "san-array-01");
        assertThat(graph.get("edges").size()).isGreaterThanOrEqualTo(6);
        for (JsonNode e : graph.get("edges")) {
            assertThat(graph.get("nodes").findValuesAsText("id")).contains(e.get("source").asText(), e.get("target").asText());
        }
        JsonNode all = getJson("riya.varma", "/api/cmdb/graph?type=BUSINESS_SERVICE");
        assertThat(all.get("nodes").findValuesAsText("ciType")).containsOnly("BUSINESS_SERVICE");
        assertThat(all.get("nodes").size()).isGreaterThanOrEqualTo(10);
        JsonNode payrollCi = getJson("riya.varma", "/api/cmdb/cis/" + ciId("Payroll"));
        assertThat(payrollCi.get("serviceCode").asText()).isEqualTo("PAYROLL");
        assertThat(payrollCi.get("outgoing").toString()).contains("SERVICE_MAP").contains("Database Platform");
        JsonNode assetsOfServer = getJson("riya.varma", "/api/cmdb/cis/" + ciId("payroll-db-primary"));
        assertThat(assetsOfServer.get("assets").findValuesAsText("assetType")).contains("SERVER");
        assertThat(assetsOfServer.get("openAlertCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(assetsOfServer.get("problems").findValuesAsText("number")).isNotEmpty(); // backfilled from the payroll problem's affected CIs
        JsonNode meta = getJson("riya.varma", "/api/cmdb/meta");
        assertThat(meta.get("relationshipTypes").findValuesAsText("type")).containsExactly("DEPENDS_ON", "RUNS_ON", "HOSTS", "CONNECTS_TO", "PART_OF");
    }

    @Test
    void businessServicesAreMirroredAsCis() throws Exception {
        String code = "SVC-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Map<String, Object> svc = new HashMap<>(Map.of("code", code, "name", "Test service " + code, "departmentId", dept("IT-APPS"), "tier", 2,
                "maintenance", false, "dependsOnIds", List.of(serviceId("DATABASE"))));
        long sid = body(mvc.perform(jsonBody(as("admin", post("/api/services")), svc)).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        long ci = jdbc.queryForObject("select id from configuration_items where service_id = ?", Long.class, sid);
        JsonNode detail = getJson("riya.varma", "/api/cmdb/cis/" + ci);
        assertThat(detail.get("ciType").asText()).isEqualTo("BUSINESS_SERVICE");
        assertThat(detail.get("name").asText()).isEqualTo("Test service " + code);
        assertThat(detail.get("criticality").asText()).isEqualTo("HIGH"); // tier 2
        assertThat(detail.get("outgoing")).hasSize(1);
        assertThat(detail.at("/outgoing/0/ci/name").asText()).isEqualTo("Database Platform");
        assertThat(detail.at("/outgoing/0/origin").asText()).isEqualTo("SERVICE_MAP");
        long rel = detail.at("/outgoing/0/id").asLong();
        mvc.perform(as("deepa.nair", delete("/api/cmdb/relationships/" + rel))).andExpect(status().isConflict());
        mvc.perform(as("deepa.nair", delete("/api/cmdb/cis/" + ci))).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("deepa.nair", put("/api/cmdb/cis/" + ci)), Map.of("name", "Renamed " + code, "ciType", "BUSINESS_SERVICE")))
                .andExpect(status().isBadRequest());

        // Renaming the service renames the CI; clearing the dependencies removes the relationship.
        svc.put("name", "Renamed service " + code);
        svc.put("dependsOnIds", List.of());
        mvc.perform(jsonBody(as("admin", put("/api/services/" + sid)), svc)).andExpect(status().isOk());
        detail = getJson("riya.varma", "/api/cmdb/cis/" + ci);
        assertThat(detail.get("name").asText()).isEqualTo("Renamed service " + code);
        assertThat(detail.get("outgoing")).isEmpty();
        assertThat(getJson("riya.varma", "/api/cmdb/cis/" + ci + "/history").findValuesAsText("action")).contains("CREATED", "RELATIONSHIP_ADDED",
                "RELATIONSHIP_REMOVED", "UPDATED");
        // Its open incidents show up in the impact analysis of the infrastructure it depends on.
        long server = createCi("deepa.nair", Map.of("name", "srv-" + code, "ciType", "SERVER")).get("id").asLong();
        relate(ci, server, "RUNS_ON");
        JsonNode incident = createIncident("riya.varma", Map.of("title", unique("Test service unavailable " + code), "description", "Pages fail to load",
                "serviceId", sid, "impact", "HIGH", "urgency", "HIGH"));
        JsonNode impact = getJson("riya.varma", "/api/cmdb/cis/" + server + "/impact?direction=downstream");
        assertThat(impact.get("impactedServices").findValuesAsText("code")).containsExactly(code);
        assertThat(impact.get("openIncidents").findValuesAsText("number")).contains(incident.at("/incident/number").asText());
        assertThat(impact.at("/impactedServices/0/status").asText()).isEqualTo("OUTAGE");
    }

    @Test
    void configurationItemCrudValidationHistoryAndPermissions() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new HashMap<>(Map.of("name", "app-" + run, "ciType", "APPLICATION", "environment", "STAGING", "criticality", "HIGH",
                "description", "Test application", "attributes", Map.of("version", "1.0", "replicas", 3, "public", false)));
        mvc.perform(jsonBody(as("riya.varma", post("/api/cmdb/cis")), body)).andExpect(status().isForbidden());
        mvc.perform(as("kavya.reddy", get("/api/cmdb/cis"))).andExpect(status().isForbidden());
        JsonNode created = createCi("deepa.nair", body);
        long id = created.get("id").asLong();
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.at("/attributes/replicas").asInt()).isEqualTo(3);
        assertThat(created.get("createdByName").asText()).isEqualTo("Deepa Nair");
        // IT managers are CMDB editors too.
        createCi("priya.raman", Map.of("name", "db-" + run, "ciType", "DATABASE", "teamId", jdbc.queryForObject("select id from teams where code = 'DB-OPS'",
                Long.class)));

        Map<String, Object> dup = new HashMap<>(body);
        dup.put("name", "APP-" + run.toUpperCase());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/cis")), dup)).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/cis")), Map.of("name", "", "ciType", "SERVER"))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/cis")), Map.of("name", "x-" + run, "ciType", "TOASTER"))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/cis")), Map.of("name", "y-" + run, "ciType", "SERVER", "attributes",
                Map.of("1bad key", "v")))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("attributes"));
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/cis")), Map.of("name", "z-" + run, "ciType", "SERVER", "attributes",
                Map.of("nested", Map.of("a", 1))))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/cis")), Map.of("name", "w-" + run, "ciType", "SERVER", "serviceId", serviceId("PAYROLL"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("serviceId"));
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/cis")), Map.of("name", "v-" + run, "ciType", "BUSINESS_SERVICE", "serviceId",
                serviceId("PAYROLL")))).andExpect(status().isConflict()); // Payroll already has its CI
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/cis")), Map.of("name", "u-" + run, "ciType", "SERVER", "teamId", 999_999)))
                .andExpect(status().isBadRequest());

        body.put("status", "MAINTENANCE");
        body.put("attributes", Map.of("version", "1.1", "replicas", 3));
        body.put("ownerId", jdbc.queryForObject("select id from users where email = ?", Long.class, email("riya.varma")));
        JsonNode updated = body(mvc.perform(jsonBody(as("deepa.nair", put("/api/cmdb/cis/" + id)), body)).andExpect(status().isOk()).andReturn());
        assertThat(updated.get("status").asText()).isEqualTo("MAINTENANCE");
        assertThat(updated.get("ownerName").asText()).isEqualTo("Riya Varma");
        JsonNode history = getJson("ananya.bose", "/api/cmdb/cis/" + id + "/history");
        assertThat(history.findValuesAsText("action")).contains("CREATED", "STATUS_CHANGED", "ATTRIBUTES_CHANGED", "UPDATED");
        assertThat(history.toString()).contains("status: ACTIVE").contains("status: MAINTENANCE").contains("Changed: public, version");

        JsonNode list = getJson("ananya.bose", "/api/cmdb/cis?search=" + run + "&type=APPLICATION,DATABASE&sort=name,asc");
        assertThat(list.get("totalElements").asInt()).isEqualTo(2);
        assertThat(list.at("/content/0/name").asText()).isEqualTo("app-" + run);
        assertThat(getJson("riya.varma", "/api/cmdb/cis?search=" + run + "&status=RETIRED").get("totalElements").asInt()).isZero();
        assertThat(getJson("riya.varma", "/api/cmdb/cis?environment=STAGING&search=" + run).get("totalElements").asInt()).isEqualTo(1);
        mvc.perform(as("riya.varma", get("/api/cmdb/cis?sort=secret"))).andExpect(status().isBadRequest());
        mvc.perform(as("riya.varma", get("/api/cmdb/cis/999999999"))).andExpect(status().isNotFound());

        mvc.perform(as("riya.varma", delete("/api/cmdb/cis/" + id))).andExpect(status().isForbidden());
        mvc.perform(as("deepa.nair", delete("/api/cmdb/cis/" + id))).andExpect(status().isNoContent());
        mvc.perform(as("deepa.nair", get("/api/cmdb/cis/" + id))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action in ('CI_CREATED', 'CI_UPDATED', 'CI_DELETED') and entity_id = ?",
                Long.class, String.valueOf(id))).isEqualTo(3);
    }

    @Test
    void relationshipsAndCycleSafeTraversal() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long a = createCi("deepa.nair", Map.of("name", "cyc-a-" + run, "ciType", "APPLICATION")).get("id").asLong();
        long b = createCi("deepa.nair", Map.of("name", "cyc-b-" + run, "ciType", "SERVER")).get("id").asLong();
        long c = createCi("deepa.nair", Map.of("name", "cyc-c-" + run, "ciType", "DATABASE")).get("id").asLong();
        long d = createCi("deepa.nair", Map.of("name", "cyc-d-" + run, "ciType", "INFRASTRUCTURE")).get("id").asLong();
        long ab = relate(a, b, "DEPENDS_ON");
        relate(b, c, "DEPENDS_ON");
        relate(c, a, "DEPENDS_ON"); // cycle a → b → c → a
        relate(d, c, "HOSTS"); // c runs on d: c depends on d
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/relationships")), Map.of("sourceId", a, "targetId", b, "type", "DEPENDS_ON")))
                .andExpect(status().isConflict());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/relationships")), Map.of("sourceId", a, "targetId", a, "type", "DEPENDS_ON")))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/relationships")), Map.of("sourceId", a, "targetId", 999_999_999, "type", "DEPENDS_ON")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("targetId"));
        mvc.perform(jsonBody(as("deepa.nair", post("/api/cmdb/relationships")), Map.of("sourceId", a, "targetId", b, "type", "LIKES")))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("riya.varma", post("/api/cmdb/relationships")), Map.of("sourceId", a, "targetId", c, "type", "CONNECTS_TO")))
                .andExpect(status().isForbidden());

        JsonNode impact = getJson("riya.varma", "/api/cmdb/cis/" + a + "/impact?depth=10");
        assertThat(names(impact.get("upstream"))).containsExactlyInAnyOrder("cyc-b-" + run, "cyc-c-" + run, "cyc-d-" + run);
        assertThat(names(impact.get("downstream"))).containsExactlyInAnyOrder("cyc-c-" + run, "cyc-b-" + run);
        assertThat(impact.get("upstream").findValuesAsText("depth")).containsExactly("1", "2", "3");
        JsonNode fromD = getJson("riya.varma", "/api/cmdb/cis/" + d + "/impact?direction=downstream");
        assertThat(names(fromD.get("downstream"))).containsExactlyInAnyOrder("cyc-c-" + run, "cyc-b-" + run, "cyc-a-" + run);
        assertThat(fromD.at("/downstream/0/via").asText()).isEqualTo("HOSTS");
        JsonNode graph = getJson("riya.varma", "/api/cmdb/graph?ciId=" + a + "&depth=3");
        assertThat(graph.get("nodes")).hasSize(4);
        assertThat(graph.get("edges")).hasSize(4);

        JsonNode rels = getJson("riya.varma", "/api/cmdb/cis/" + a + "/relationships");
        assertThat(rels.findValuesAsText("direction")).containsExactlyInAnyOrder("OUTGOING", "INCOMING");
        assertThat(rels.findValuesAsText("label")).contains("depends on", "is needed by");
        mvc.perform(as("deepa.nair", delete("/api/cmdb/relationships/" + ab))).andExpect(status().isNoContent());
        mvc.perform(as("deepa.nair", delete("/api/cmdb/relationships/" + ab))).andExpect(status().isNotFound());
        assertThat(names(getJson("riya.varma", "/api/cmdb/cis/" + a + "/impact?direction=upstream").get("upstream"))).isEmpty();
        assertThat(getJson("riya.varma", "/api/cmdb/cis/" + a + "/history").findValuesAsText("action")).contains("RELATIONSHIP_ADDED", "RELATIONSHIP_REMOVED");
    }

    @Test
    void problemsAndChangesLinkConfigurationItems() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long ci = createCi("deepa.nair", Map.of("name", "linked-" + run, "ciType", "SERVER")).get("id").asLong();
        // Explicit ids: the CI name is added to the free-text list.
        JsonNode problem = body(mvc.perform(jsonBody(as("riya.varma", post("/api/problems")), Map.of("title", "Problem with CI links " + run,
                "affectedCis", List.of("payroll-app-01", "not-a-ci-" + run), "ciIds", List.of(ci)))).andExpect(status().isCreated()).andReturn());
        assertThat(problem.get("cis").findValuesAsText("name")).containsExactly("linked-" + run);
        assertThat(problem.get("affectedCis").toString()).contains("payroll-app-01", "not-a-ci-" + run, "linked-" + run);
        long problemId = problem.get("id").asLong();
        // Without ids the links follow the names (exact match ignoring case).
        JsonNode byName = body(mvc.perform(jsonBody(as("riya.varma", put("/api/problems/" + problemId)), Map.of("title", "Problem with CI links " + run,
                "affectedCis", List.of("PAYROLL-APP-01", "later-" + run)))).andExpect(status().isOk()).andReturn());
        assertThat(byName.get("cis").findValuesAsText("name")).containsExactly("payroll-app-01");
        mvc.perform(jsonBody(as("riya.varma", put("/api/problems/" + problemId)), Map.of("title", "Problem with CI links " + run, "ciIds", List.of(999_999_999))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("ciIds"));
        // A CI created later with a name the problem already uses is linked automatically.
        long later = createCi("deepa.nair", Map.of("name", "later-" + run, "ciType", "APPLICATION")).get("id").asLong();
        assertThat(getJson("riya.varma", "/api/problems/" + problemId).get("cis").findValuesAsText("name")).contains("later-" + run, "payroll-app-01");
        assertThat(getJson("riya.varma", "/api/cmdb/cis/" + later).get("problems").findValuesAsText("id")).contains(String.valueOf(problemId));

        JsonNode change = body(mvc.perform(jsonBody(as("anil.kumar", post("/api/changes")), Map.of("title", "Patch the linked server " + run,
                "type", "NORMAL", "ciIds", List.of(ci)))).andExpect(status().isCreated()).andReturn());
        assertThat(change.get("cis").findValuesAsText("name")).containsExactly("linked-" + run);
        assertThat(change.get("affectedCis").toString()).contains("linked-" + run);
        assertThat(getJson("riya.varma", "/api/cmdb/cis/" + ci).get("changes").findValuesAsText("number")).contains(change.get("number").asText());
        // Release 2 / BE-2 style requests (names only) keep working.
        JsonNode legacy = body(mvc.perform(jsonBody(as("anil.kumar", post("/api/changes")), Map.of("title", "Name-only change " + run,
                "affectedCis", List.of("core-sw-01")))).andExpect(status().isCreated()).andReturn());
        assertThat(legacy.get("cis").findValuesAsText("name")).containsExactly("core-sw-01");
    }
}

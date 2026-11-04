package com.nexus360.twin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.observability.ObservabilityTestSupport;
import com.nexus360.observability.OtlpPayloads;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Digital twin: graph with layers and state, business-service tree, failure simulations with and without redundancy, change timeline. */
class TwinIT extends ObservabilityTestSupport {

    private JsonNode simulate(String user, Map<String, Object> req, int expected) throws Exception {
        return body(mvc.perform(jsonBody(as(user, post("/api/twin/simulations")), req)).andExpect(status().is(expected)).andReturn());
    }

    private static Map<String, String> impacts(JsonNode result) {
        Map<String, String> out = new HashMap<>();
        result.get("impactedCis").forEach(c -> out.put(c.get("name").asText(), c.get("impact").asText()));
        return out;
    }

    @Test
    void graphHasLayersStateAndDemoRedundancy() throws Exception {
        long payroll = jdbc.queryForObject("select id from business_services where code = 'PAYROLL'", Long.class);
        long payrollCi = jdbc.queryForObject("select id from configuration_items where service_id = ?", Long.class, payroll);
        JsonNode g = getJson("ananya.bose", "/api/twin/graph?root=" + payrollCi + "&depth=4");
        assertThat(g.get("nodes").size()).isGreaterThan(3);
        JsonNode root = null;
        for (JsonNode n : g.get("nodes")) {
            if (n.get("id").asLong() == payrollCi) {
                root = n;
            }
        }
        assertThat(root).isNotNull();
        assertThat(root.get("layer").asText()).isEqualTo("BUSINESS");
        assertThat(root.get("state").get("health").asText()).isNotBlank();
        assertThat(g.get("layerCounts").get("INFRASTRUCTURE").asLong()).isPositive();
        boolean redundantEdge = false;
        for (JsonNode e : g.get("edges")) {
            redundantEdge |= e.get("redundant").asBoolean();
        }
        assertThat(redundantEdge).as("demo relationships carry redundant: true").isTrue();
        JsonNode infra = getJson("riya.varma", "/api/twin/graph?root=" + payrollCi + "&depth=4&layer=INFRASTRUCTURE&includeState=false");
        infra.get("nodes").forEach(n -> assertThat(n.get("layer").asText()).isIn("INFRASTRUCTURE", "BUSINESS"));
        mvc.perform(as("riya.varma", org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/twin/graph?layer=CLOUD")))
                .andExpect(status().isBadRequest());

        JsonNode tree = getJson("riya.varma", "/api/twin/business-services/" + payroll);
        assertThat(tree.get("service").get("code").asText()).isEqualTo("PAYROLL");
        assertThat(tree.toString()).contains("payroll-db-primary").contains("san-array-01");
        assertThat(tree.get("root").get("layer").asText()).isEqualTo("BUSINESS");
        assertThat(tree.get("layerCounts").get("APPLICATION").asLong()).isPositive();
    }

    @Test
    void failureSimulationPropagatesAndRedundancyReducesImpact() throws Exception {
        String u = uid();
        long[] svc = service(("TWN" + u).toUpperCase());
        long app = ci("twin-app-" + u, "APPLICATION");
        long n1 = ci("twin-node1-" + u, "SERVER");
        long n2 = ci("twin-node2-" + u, "SERVER");
        long db = ci("twin-db-" + u, "DATABASE");
        relationship(svc[1], app, "DEPENDS_ON");
        long r1 = relationship(app, n1, "RUNS_ON");
        long r2 = relationship(app, n2, "RUNS_ON");
        relationship(app, db, "DEPENDS_ON");
        String serviceName = "Service TWN" + u.toUpperCase();

        // Without redundancy: a node failure takes the application and the service down.
        JsonNode hard = simulate("ananya.bose", Map.of("type", "FAILURE", "targets", List.of(n1), "mode", "HARD"), 201);
        assertThat(hard.get("label").asText()).isEqualTo("Simulation");
        assertThat(impacts(hard)).containsEntry("twin-app-" + u, "DOWN").containsEntry(serviceName, "DOWN");
        assertThat(hard.get("impactedServices").get(0).get("serviceId").asLong()).isEqualTo(svc[0]);
        assertThat(hard.get("impactedServices").get(0).get("affectedUsersBasis").asText()).isEqualTo("UNKNOWN");
        JsonNode appImpact = null;
        for (JsonNode c : hard.get("impactedCis")) {
            if (c.get("ciId").asLong() == app) {
                appImpact = c;
            }
        }
        assertThat(appImpact.get("path").get(0).asText()).isEqualTo("twin-node1-" + u);
        assertThat(appImpact.get("redundancyApplied").asBoolean()).isFalse();

        // Mark both node relationships redundant (twin editors only).
        mvc.perform(jsonBody(as("ananya.bose", put("/api/twin/relationships/" + r1)), Map.of("redundant", true))).andExpect(status().isForbidden());
        JsonNode edge = body(mvc.perform(jsonBody(as("deepa.nair", put("/api/twin/relationships/" + r1)), Map.of("redundant", true))).andExpect(status().isOk())
                .andReturn());
        assertThat(edge.get("redundant").asBoolean()).isTrue();
        mvc.perform(jsonBody(as("deepa.nair", put("/api/twin/relationships/" + r2)), Map.of("redundant", true))).andExpect(status().isOk());

        JsonNode redundant = simulate("riya.varma", Map.of("type", "FAILURE", "targets", List.of(n1), "mode", "HARD"), 201);
        assertThat(impacts(redundant)).containsEntry("twin-app-" + u, "DEGRADED").containsEntry(serviceName, "DEGRADED");
        JsonNode bothNodes = simulate("riya.varma", Map.of("type", "FAILURE", "targets", List.of(n1, n2), "mode", "HARD"), 201);
        assertThat(impacts(bothNodes)).containsEntry("twin-app-" + u, "DOWN");
        JsonNode degradedNode = simulate("riya.varma", Map.of("type", "FAILURE", "targets", List.of(n1), "mode", "DEGRADED"), 201);
        assertThat(impacts(degradedNode)).doesNotContainKey("twin-app-" + u);
        assertThat(degradedNode.get("notes").toString()).contains("redundant");
        // The database is not redundant.
        assertThat(impacts(simulate("riya.varma", Map.of("type", "FAILURE", "targets", List.of(db)), 201))).containsEntry(serviceName, "DOWN");

        // Cluster membership: two cache nodes in cluster "c-<u>"; the dependent only links one of them.
        long c1 = jdbc.queryForObject("insert into configuration_items (name, ci_type, attributes) values (?, 'SERVER', ?::jsonb) returning id", Long.class,
                "twin-cache1-" + u, "{\"cluster\": \"c-" + u + "\"}");
        jdbc.update("insert into configuration_items (name, ci_type, attributes) values (?, 'SERVER', ?::jsonb)", "twin-cache2-" + u, "{\"cluster\": \"c-" + u
                + "\"}");
        long cacheUser = ci("twin-cacheuser-" + u, "APPLICATION");
        relationship(cacheUser, c1, "DEPENDS_ON");
        assertThat(impacts(simulate("riya.varma", Map.of("type", "FAILURE", "targets", List.of(c1)), 201))).containsEntry("twin-cacheuser-" + u, "DEGRADED");

        // Persisted, listed, readable; never changes real data.
        long id = redundant.get("id").asLong();
        JsonNode stored = getJson("ananya.bose", "/api/twin/simulations/" + id);
        assertThat(stored.get("createdByName").asText()).isEqualTo("Riya Varma");
        assertThat(impacts(stored)).containsEntry("twin-app-" + u, "DEGRADED");
        assertThat(getJson("ananya.bose", "/api/twin/simulations?limit=50").toString()).contains("\"id\":" + id);
        assertThat(jdbc.queryForObject("select status from configuration_items where id = ?", String.class, n1)).isEqualTo("ACTIVE");

        // Validation and permissions.
        simulate("riya.varma", Map.of("type", "FAILURE", "targets", List.of()), 400);
        simulate("riya.varma", Map.of("type", "MELTDOWN", "targets", List.of(n1)), 400);
        simulate("riya.varma", Map.of("type", "FAILURE", "targets", List.of(99_999_999)), 400);
        simulate("rohan.kulkarni", Map.of("type", "FAILURE", "targets", List.of(n1)), 403);

        // CHANGE simulation: targets derived from the change's CIs; scheduled changes touching impacted components are listed.
        long change = jdbc.queryForObject("insert into changes (number, title, change_type, status, planned_start, planned_end) values (?, ?, 'NORMAL', "
                + "'SCHEDULED', now() + interval '1 day', now() + interval '1 day 2 hours') returning id", Long.class, "CHG-T" + u, "Patch twin db " + u);
        jdbc.update("insert into change_cis (change_id, ci_id) values (?, ?)", change, db);
        long other = jdbc.queryForObject("insert into changes (number, title, change_type, status, planned_start, planned_end) values (?, ?, 'NORMAL', "
                + "'APPROVED', now() + interval '2 days', now() + interval '2 days 1 hour') returning id", Long.class, "CHG-U" + u, "App upgrade " + u);
        jdbc.update("insert into change_cis (change_id, ci_id) values (?, ?)", other, app);
        JsonNode cs = simulate("riya.varma", Map.of("type", "CHANGE", "changeId", change), 201);
        assertThat(cs.get("mode").asText()).isEqualTo("DEGRADED");
        assertThat(cs.get("changeNumber").asText()).isEqualTo("CHG-T" + u);
        assertThat(cs.get("targets").get(0).get("id").asLong()).isEqualTo(db);
        assertThat(impacts(cs)).containsEntry("twin-app-" + u, "DEGRADED");
        List<String> changes = new ArrayList<>();
        cs.get("scheduledChanges").forEach(c -> changes.add(c.get("number").asText()));
        assertThat(changes).contains("CHG-U" + u).doesNotContain("CHG-T" + u);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'TWIN_SIMULATION_RUN'", Long.class)).isGreaterThanOrEqualTo(7);
    }

    @Test
    void changeTimelineMergesCmdbChangesDeploymentsAndTelemetry() throws Exception {
        String u = uid();
        long[] svc = service(("TML" + u).toUpperCase());
        // CMDB history: an attribute change on the service CI.
        String token = otlpSource("Twin timeline " + u);
        String serviceName = "twin-timeline-" + u;
        mvc.perform(jsonBody(as("admin", post("/api/observability/mappings")), Map.of("name", "map " + serviceName, "attributeKey", "service.name",
                "matchType", "EXACT", "matchValue", serviceName, "serviceId", svc[0], "ciId", svc[1]))).andExpect(status().isCreated());
        java.time.Instant t = clock.instant().minusSeconds(120);
        export("metrics", token, OtlpPayloads.metrics(resource(serviceName, "service.version", "3.0.0"), List.of(new OtlpPayloads.Gauge("tl", null, Map.of(), t,
                1))));
        export("metrics", token, OtlpPayloads.metrics(resource(serviceName, "service.version", "3.1.0"), List.of(new OtlpPayloads.Gauge("tl", null, Map.of(),
                t.plusSeconds(60), 1))));
        mvc.perform(jsonBody(as("anil.kumar", post("/api/deployments")), Map.of("serviceId", svc[0], "environment", "PRODUCTION", "version", "3.0.0",
                "status", "SUCCEEDED", "startedAt", t.minusSeconds(3600).toString()))).andExpect(status().isCreated());
        mvc.perform(jsonBody(as("anil.kumar", post("/api/deployments")), Map.of("serviceId", svc[0], "environment", "PRODUCTION", "version", "3.1.0",
                "status", "SUCCEEDED"))).andExpect(status().isCreated());
        long change = jdbc.queryForObject("insert into changes (number, title, change_type) values (?, ?, 'NORMAL') returning id", Long.class, "CHG-L" + u,
                "Upgrade " + u);
        jdbc.update("insert into change_services (change_id, service_id) values (?, ?)", change, svc[0]);
        jdbc.update("insert into change_history (change_id, actor_name, action, from_value, to_value, created_at) values (?, 'Anil Kumar', 'STATUS_CHANGED', "
                + "'DRAFT', 'SUBMITTED', ?)", change, Timestamp.from(clock.instant()));

        JsonNode timeline = getJson("ananya.bose", "/api/twin/changes?ciId=" + svc[1]);
        List<String> kinds = new ArrayList<>();
        timeline.get("entries").forEach(e -> kinds.add(e.get("kind").asText()));
        assertThat(kinds).contains("CMDB", "CHANGE", "DEPLOYMENT", "TELEMETRY");
        JsonNode telemetry = null;
        JsonNode deployment = null;
        for (JsonNode e : timeline.get("entries")) {
            if (e.get("kind").asText().equals("TELEMETRY")) {
                telemetry = e;
            }
            if (e.get("kind").asText().equals("DEPLOYMENT") && e.get("diff").get(0).get("to").asText().equals("3.1.0")) {
                deployment = e;
            }
        }
        assertThat(telemetry.get("diff").get(0).get("field").asText()).isEqualTo("service.version");
        assertThat(telemetry.get("diff").get(0).get("from").asText()).isEqualTo("3.0.0");
        assertThat(telemetry.get("diff").get(0).get("to").asText()).isEqualTo("3.1.0");
        assertThat(deployment.get("diff").get(0).get("from").asText()).isEqualTo("3.0.0");
        // Newest first.
        for (int i = 1; i < timeline.get("entries").size(); i++) {
            assertThat(java.time.Instant.parse(timeline.get("entries").get(i - 1).get("at").asText()))
                    .isAfterOrEqualTo(java.time.Instant.parse(timeline.get("entries").get(i).get("at").asText()));
        }
        assertThat(getJson("riya.varma", "/api/twin/changes?serviceId=" + svc[0]).get("entries").size()).isGreaterThanOrEqualTo(4);
        mvc.perform(as("rohan.kulkarni", org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/twin/changes?ciId=" + svc[1])))
                .andExpect(status().isForbidden());
    }
}

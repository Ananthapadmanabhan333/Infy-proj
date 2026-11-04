package com.nexus360.cmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Service dependencies: validation (self, cycles), update semantics, dependents, derived degradation and history. */
class ServiceDependencyIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private Map<String, Object> service(String code, String name, List<Long> dependsOnIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("description", "Test service " + name);
        body.put("departmentId", jdbc.queryForObject("select id from departments where code = 'IT-APPS'", Long.class));
        body.put("tier", 2);
        body.put("keywords", List.of(code.toLowerCase(Locale.ROOT)));
        body.put("defaultCategory", "APPLICATION");
        body.put("maintenance", false);
        if (dependsOnIds != null) {
            body.put("dependsOnIds", dependsOnIds);
        }
        return body;
    }

    private JsonNode save(String user, Long id, Map<String, Object> body, int expected) throws Exception {
        var request = id == null ? post("/api/services") : put("/api/services/" + id);
        return body(mvc.perform(jsonBody(as(user, request), body)).andExpect(status().is(expected)).andReturn());
    }

    private JsonNode detail(long id) throws Exception {
        return getJson("riya.varma", "/api/services/" + id);
    }

    @Test
    void dependenciesAreValidatedAndDriveDerivedStatus() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        String coreCode = "CORE-" + suffix;
        String appCode = "APP-" + suffix;
        save("priya.raman", null, service(coreCode, "Core ledger " + suffix, null), 403);
        long core = save("rajesh.iyer", null, service(coreCode, "Core ledger " + suffix, null), 201).get("id").asLong();
        JsonNode app = save("rajesh.iyer", null, service(appCode, "Ledger portal " + suffix, List.of(core)), 201);
        long appId = app.get("id").asLong();
        assertThat(app.get("dependsOnIds").get(0).asLong()).isEqualTo(core);
        assertThat(app.get("defaultCategory").asText()).isEqualTo("APPLICATION");

        save("rajesh.iyer", core, service(coreCode, "Core ledger " + suffix, List.of(core)), 400); // itself
        JsonNode cycle = save("rajesh.iyer", core, service(coreCode, "Core ledger " + suffix, List.of(appId)), 400);
        assertThat(cycle.get("message").asText()).contains("cycle");
        save("rajesh.iyer", core, service(coreCode, "Core ledger " + suffix, List.of(999_999L)), 400);

        JsonNode coreDetail = detail(core);
        assertThat(coreDetail.get("dependents").findValuesAsText("code")).containsExactly(appCode);
        assertThat(coreDetail.get("dependencies")).isEmpty();
        assertThat(detail(appId).get("dependencies").findValuesAsText("code")).containsExactly(coreCode);
        assertThat(coreDetail.get("availabilityHistory")).hasSize(90);
        assertThat(coreDetail.at("/availabilityHistory/89/availability").asDouble()).isEqualTo(100.0);

        // A critical incident on the dependency puts it in outage and degrades the dependent service.
        long incident = createIncident("ishaan.malhotra", Map.of("title", unique("Core ledger postings rejected"),
                "description", "All postings fail", "serviceId", core, "impact", "HIGH", "urgency", "HIGH")).at("/incident/id").asLong();
        try {
            JsonNode coreNow = detail(core);
            assertThat(coreNow.get("status").asText()).isEqualTo("OUTAGE");
            assertThat(coreNow.get("statusReason").asText()).isEqualTo("1 critical incident(s) open");
            JsonNode appNow = detail(appId);
            assertThat(appNow.get("status").asText()).isEqualTo("DEGRADED");
            assertThat(appNow.get("statusReason").asText()).isEqualTo("Dependency Core ledger " + suffix + " is in outage");
            assertThat(appNow.at("/dependencies/0/status").asText()).isEqualTo("OUTAGE");
        } finally {
            mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + incident + "/transition")), Map.of("status", "RESOLVED",
                    "resolutionCode", "FIXED", "resolutionNotes", "Posting queue restarted.")));
        }
        assertThat(detail(appId).get("status").asText()).isEqualTo("OPERATIONAL");
        assertThat(detail(appId).get("statusReason").isNull()).isTrue();

        // A missing dependsOnIds keeps the dependencies; an empty list clears them.
        assertThat(save("rajesh.iyer", appId, service(appCode, "Ledger portal " + suffix + " renamed", null), 200)
                .get("dependsOnIds")).hasSize(1);
        assertThat(save("rajesh.iyer", appId, service(appCode, "Ledger portal " + suffix, List.of()), 200).get("dependsOnIds")).isEmpty();
        assertThat(detail(core).get("dependents")).isEmpty();
    }
}

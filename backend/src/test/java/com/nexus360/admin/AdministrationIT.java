package com.nexus360.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Administration Release 2: system health, AI configuration, organisation settings and incident categories. */
class AdministrationIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void systemHealthIsForAdministratorsOnly() throws Exception {
        JsonNode health = getJson("rajesh.iyer", "/api/admin/system-health");
        assertThat(health.get("status").asText()).isEqualTo("UP");
        assertThat(health.get("version").asText()).isEqualTo("2.0.0");
        assertThat(health.get("uptimeSeconds").asLong()).isNotNegative();
        assertThat(health.at("/database/status").asText()).isEqualTo("UP");
        assertThat(health.at("/database/schemaVersion").asText()).isEqualTo("13"); // V13__release4_finops_dex
        // Release 3: no MAIL_HOST in tests, so e-mail is reported as not configured (never as sent).
        assertThat(health.at("/email/configured").asBoolean()).isFalse();
        assertThat(health.at("/email/status").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(health.at("/reportScheduler/enabled").asBoolean()).isFalse(); // disabled in tests
        assertThat(health.at("/redis/enabled").isBoolean()).isTrue();
        assertThat(health.at("/slaScheduler/enabled").asBoolean()).isFalse(); // disabled in tests
        assertThat(health.at("/workflowEngine/pendingRetries").isNumber()).isTrue();
        assertThat(health.at("/ai/keyConfigured").asBoolean()).isFalse();
        assertThat(health.at("/counts/users").asLong()).isPositive();
        assertThat(getJson("admin", "/api/admin/system-health").get("status").asText()).isEqualTo("UP");
        for (String user : List.of("priya.raman", "sunita.joshi", "riya.varma", "ananya.bose", "kavya.reddy")) {
            mvc.perform(as(user, get("/api/admin/system-health"))).andExpect(status().isForbidden());
        }
    }

    @Test
    void aiConfigurationNeverAcceptsOrReturnsKeys() throws Exception {
        JsonNode ai = getJson("admin", "/api/admin/ai");
        assertThat(ai.get("keyConfigured").asBoolean()).isFalse();
        assertThat(ai.get("rateLimitPerMinute").asInt()).isEqualTo(30);
        assertThat(ai.toString().toLowerCase()).doesNotContain("apikey").doesNotContain("sk-");
        mvc.perform(as("rajesh.iyer", get("/api/admin/ai"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("admin", put("/api/admin/ai")), Map.of("llmEnabled", false, "apiKey", "sk-test")))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("admin", put("/api/admin/ai")), Map.of("llmEnabled", "no"))).andExpect(status().isBadRequest());
        try {
            JsonNode off = body(mvc.perform(jsonBody(as("admin", put("/api/admin/ai")), Map.of("llmEnabled", false)))
                    .andExpect(status().isOk()).andReturn());
            assertThat(off.get("llmEnabled").asBoolean()).isFalse();
        } finally {
            mvc.perform(jsonBody(as("admin", put("/api/admin/ai")), Map.of("llmEnabled", true))).andExpect(status().isOk());
        }
        assertThat(getJson("admin", "/api/admin/ai").get("llmEnabled").asBoolean()).isTrue();
    }

    @Test
    void organisationNameIsValidatedAndShownToEveryUser() throws Exception {
        assertThat(getJson("kavya.reddy", "/api/auth/me").at("/organisation/name").asText()).isEqualTo("SA");
        mvc.perform(jsonBody(as("admin", put("/api/admin/settings/org.name")), Map.of("value", " "))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("admin", put("/api/admin/settings/org.timezone")), Map.of("value", "Mars/Olympus")))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("admin", put("/api/admin/settings/org.support_email")), Map.of("value", "not-an-email")))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/admin/settings/org.name")), Map.of("value", "Acme")))
                .andExpect(status().isForbidden());
        try {
            mvc.perform(jsonBody(as("admin", put("/api/admin/settings/org.name")), Map.of("value", "Acme Shared Services")))
                    .andExpect(status().isOk());
            assertThat(getJson("kavya.reddy", "/api/auth/me").at("/organisation/name").asText()).isEqualTo("Acme Shared Services");
        } finally {
            mvc.perform(jsonBody(as("admin", put("/api/admin/settings/org.name")), Map.of("value", "SA"))).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'SETTINGS_CHANGED' and entity_id = 'org.name'",
                Long.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void incidentCategoriesConfigureClassificationAndRouting() throws Exception {
        JsonNode original = getJson("riya.varma", "/api/admin/incident-categories");
        assertThat(original).hasSize(9);
        assertThat(original.findValuesAsText("code")).contains("APPLICATION", "HARDWARE", "FACILITIES", "OTHER");
        mvc.perform(as("kavya.reddy", get("/api/admin/incident-categories"))).andExpect(status().isForbidden());

        List<Map<String, Object>> restore = new ArrayList<>();
        for (JsonNode c : original) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("code", c.get("code").asText());
            entry.put("name", c.get("name").asText());
            entry.put("description", c.get("description").isNull() ? null : c.get("description").asText());
            entry.put("defaultDepartmentId", c.get("defaultDepartmentId").isNull() ? null : c.get("defaultDepartmentId").asLong());
            entry.put("keywords", json.convertValue(c.get("keywords"), List.class));
            entry.put("active", c.get("active").asBoolean());
            restore.add(entry);
        }
        long fac = jdbc.queryForObject("select id from departments where code = 'FAC'", Long.class);
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> entry : restore) {
            Map<String, Object> copy = new HashMap<>(entry);
            if ("HARDWARE".equals(copy.get("code"))) {
                copy.put("keywords", List.of("zorblax", "quibbit"));
                copy.put("defaultDepartmentId", fac);
            }
            if ("SECURITY".equals(copy.get("code"))) {
                copy.put("active", false);
            }
            changed.add(copy);
        }

        mvc.perform(jsonBody(as("priya.raman", put("/api/admin/incident-categories")), changed)).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/admin/incident-categories")), List.of(Map.of("code", "BOGUS", "name", "Bogus", "active", true))))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/admin/incident-categories")),
                List.of(Map.of("code", "OTHER", "name", "Other", "active", false)))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/admin/incident-categories")), List.of())).andExpect(status().isBadRequest());
        try {
            JsonNode updated = body(mvc.perform(jsonBody(as("rajesh.iyer", put("/api/admin/incident-categories")), changed))
                    .andExpect(status().isOk()).andReturn());
            assertThat(updated).hasSize(9);
            for (JsonNode c : updated) {
                if ("HARDWARE".equals(c.get("code").asText())) {
                    assertThat(c.get("keywords").get(0).asText()).isEqualTo("zorblax");
                    assertThat(c.get("defaultDepartmentId").asLong()).isEqualTo(fac);
                }
            }

            JsonNode classified = createIncident("manoj.verma", Map.of("title", "Zorblax quibbit rattles near the window",
                    "description", "The zorblax quibbit rattles loudly")).get("incident");
            assertThat(classified.get("category").asText()).isEqualTo("HARDWARE");
            assertThat(classified.get("departmentId").asLong()).isEqualTo(fac);
            mvc.perform(jsonBody(as("manoj.verma", org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/incidents")),
                    Map.of("title", unique("Phishing mail received"), "description", "Suspicious link", "category", "SECURITY")))
                    .andExpect(status().isBadRequest()); // inactive category
            assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'INCIDENT_CATEGORIES_UPDATED'", Long.class)).isPositive();
        } finally {
            mvc.perform(jsonBody(as("rajesh.iyer", put("/api/admin/incident-categories")), restore)).andExpect(status().isOk());
        }
        JsonNode restored = getJson("riya.varma", "/api/admin/incident-categories");
        assertThat(restored.toString()).doesNotContain("zorblax");
    }
}

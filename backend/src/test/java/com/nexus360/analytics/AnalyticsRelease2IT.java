package com.nexus360.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** Analytics Release 2: department filter, resolution rate, first-call resolution, service volume and audited exports. */
class AnalyticsRelease2IT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long user(String name) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email(name));
    }

    /** Assigns within the test department (an assignment without departmentId moves the incident to the assignee's department). */
    private void assign(long incident, String user, long department) throws Exception {
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + incident + "/assign")),
                Map.of("assigneeId", user(user), "departmentId", department))).andExpect(status().isOk());
    }

    private void resolve(long incident) throws Exception {
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + incident + "/transition")), Map.of("status", "RESOLVED",
                "resolutionCode", "FIXED", "resolutionNotes", "Resolved for the analytics test."))).andExpect(status().isOk());
    }

    @Test
    void departmentKpisVolumeAndExports() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        jdbc.update("insert into departments (code, name) values (?, ?)", "ANL-" + suffix, "Analytics Test " + suffix);
        long dept = jdbc.queryForObject("select id from departments where code = ?", Long.class, "ANL-" + suffix);
        // One service per incident: incidents on one service with shared symptoms would be linked as duplicates.
        long[] services = new long[4];
        for (int i = 0; i < 4; i++) {
            services[i] = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/services")), Map.of("code", "ANL" + i + "-" + suffix,
                    "name", "Analytics service " + i + " " + suffix, "departmentId", dept, "tier", 3, "keywords", List.of(),
                    "maintenance", false))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        }

        long first = createIncident("sanjay.menon", Map.of("title", "Budget template macro error", "description",
                "Macro stops at line 4", "serviceId", services[0], "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();
        long second = createIncident("sanjay.menon", Map.of("title", "Forecast workbook external links broken", "description",
                "External links show REF", "serviceId", services[1], "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();
        long cancelled = createIncident("sanjay.menon", Map.of("title", "Pivot cache refresh frequency question", "description",
                "How often does it refresh", "serviceId", services[2], "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();
        createIncident("sanjay.menon", Map.of("title", "Variance percentage column disappeared", "description",
                "The variance percentage column is gone", "serviceId", services[3], "impact", "LOW", "urgency", "LOW"));

        assign(first, "riya.varma", dept);
        resolve(first); // one assignee: first-call resolution
        assign(second, "riya.varma", dept);
        assign(second, "dev.nair", dept);
        resolve(second); // reassigned: not first-call
        mvc.perform(jsonBody(as("sanjay.menon", post("/api/incidents/" + cancelled + "/transition")),
                Map.of("status", "CLOSED", "comment", "Found the answer in the wiki"))).andExpect(status().isOk());

        JsonNode overview = getJson("priya.raman", "/api/analytics/overview?days=7&departmentId=" + dept);
        JsonNode kpis = overview.get("kpis");
        assertThat(overview.get("departmentId").asLong()).isEqualTo(dept);
        assertThat(kpis.get("createdInPeriod").asLong()).isEqualTo(4);
        assertThat(kpis.get("resolvedInPeriod").asLong()).isEqualTo(2);
        // Resolved or closed, not cancelled, out of all incidents created in the period: 2 of 4.
        assertThat(kpis.get("resolutionRate").asDouble()).isEqualTo(50.0);
        assertThat(kpis.get("firstCallResolution").asDouble()).isEqualTo(50.0);
        List<String> names = new java.util.ArrayList<>();
        overview.get("serviceNames").forEach(n -> names.add(n.asText()));
        assertThat(names).hasSize(4).allMatch(n -> n.startsWith("Analytics service ") && n.endsWith(suffix));
        JsonNode volume = overview.get("serviceVolume");
        assertThat(volume).hasSize(7);
        long total = 0;
        for (JsonNode day : volume) {
            assertThat(day.get("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
            for (String name : names) {
                total += day.at("/counts").path(name).asLong();
            }
        }
        assertThat(total).isEqualTo(4);
        assertThat(getJson("priya.raman", "/api/analytics/dashboard?days=7&departmentId=" + dept).at("/kpis/firstCallResolution").asDouble())
                .isEqualTo(50.0);

        String today = overview.get("to").asText();
        JsonNode explicit = getJson("priya.raman", "/api/analytics/overview?from=" + today + "&to=" + today + "&departmentId=" + dept);
        assertThat(explicit.get("from").asText()).isEqualTo(today);
        assertThat(explicit.get("days").asInt()).isEqualTo(1);
        assertThat(explicit.at("/kpis/createdInPeriod").asLong()).isEqualTo(4);
        mvc.perform(as("priya.raman", get("/api/analytics/overview?from=2026-05-10&to=2026-05-01"))).andExpect(status().isBadRequest());
        mvc.perform(as("priya.raman", get("/api/analytics/overview?from=2024-01-01&to=2026-05-01"))).andExpect(status().isBadRequest());
        mvc.perform(as("priya.raman", get("/api/analytics/overview?departmentId=999999"))).andExpect(status().isBadRequest());

        long exportsBefore = jdbc.queryForObject("select count(*) from audit_logs where action = 'ANALYTICS_EXPORTED'", Long.class);
        MvcResult pdf = mvc.perform(as("priya.raman", get("/api/analytics/export?format=pdf&days=7&departmentId=" + dept)))
                .andExpect(status().isOk()).andReturn();
        assertThat(pdf.getResponse().getContentType()).startsWith("application/pdf");
        assertThat(pdf.getResponse().getHeader("Content-Disposition")).contains(".pdf");
        byte[] bytes = pdf.getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        MvcResult csv = mvc.perform(as("ananya.bose", get("/api/analytics/export?format=csv&days=7&departmentId=" + dept)))
                .andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        String rows = csv.getResponse().getContentAsString();
        assertThat(rows).contains("Budget template macro error").contains("Forecast workbook external links broken");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ANALYTICS_EXPORTED'", Long.class))
                .isEqualTo(exportsBefore + 2);
        mvc.perform(as("priya.raman", get("/api/analytics/export?format=xlsx"))).andExpect(status().isBadRequest());
        mvc.perform(as("riya.varma", get("/api/analytics/export?format=pdf"))).andExpect(status().isForbidden());
    }
}

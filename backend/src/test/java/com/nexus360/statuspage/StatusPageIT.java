package com.nexus360.statuspage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Status page (docs/RELEASE3_SPEC.md §4): internal and public views, editors, maintenance windows, uptime and subscriptions. */
class StatusPageIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private JsonNode settings() throws Exception {
        return getJson("admin", "/api/status-page").get("settings");
    }

    private void publish(boolean enabled) throws Exception {
        JsonNode s = settings();
        mvc.perform(jsonBody(as("admin", put("/api/status-page/settings")), Map.of("title", s.get("title").asText(),
                "description", s.get("description").isNull() ? "" : s.get("description").asText(), "publicEnabled", enabled)))
                .andExpect(status().isOk());
    }

    /** A service with its own status component (so tests do not disturb the demo services). */
    private long[] serviceWithComponent(boolean publicVisible) throws Exception {
        String code = "SP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        long service = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/services")), Map.of("code", code, "name", "Status test " + code,
                "departmentId", jdbc.queryForObject("select id from departments where code = 'IT-APPS'", Long.class), "tier", 3,
                "keywords", List.of(), "maintenance", false))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        long component = body(mvc.perform(jsonBody(as("sunita.joshi", post("/api/status-page/components")), Map.of("serviceId", service,
                "displayName", "Status test " + code, "groupName", "Tests", "publicVisible", publicVisible)))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        return new long[]{service, component};
    }

    @Test
    void publicPageExistsOnlyWhilePublishedAndShowsPublicDataOnly() throws Exception {
        boolean wasPublic = settings().get("publicEnabled").asBoolean();
        long[] internal = serviceWithComponent(false);
        long[] visible = serviceWithComponent(true);
        try {
            publish(false);
            mvc.perform(get("/api/public/status")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("The status page is not published"));
            mvc.perform(jsonBody(post("/api/public/status/subscribe"), Map.of("email", "someone@example.org"))).andExpect(status().isNotFound());

            publish(true);
            JsonNode page = body(mvc.perform(get("/api/public/status")).andExpect(status().isOk()).andReturn());
            List<String> names = page.get("components").findValuesAsText("name");
            assertThat(names).contains("Status test " + jdbc.queryForObject("select code from business_services where id = ?", String.class, visible[0]));
            assertThat(names).doesNotContain("Status test " + jdbc.queryForObject("select code from business_services where id = ?", String.class, internal[0]));
            assertThat(names).doesNotContain("Database Platform"); // demo component marked internal
            assertThat(page.get("overall").asText()).isIn("OPERATIONAL", "DEGRADED", "OUTAGE", "MAINTENANCE");
            assertThat(page.get("subscriptionsAvailable").asBoolean()).isFalse(); // no SMTP in this context
            assertThat(page.toString()).doesNotContain("statusReason", "incidentNumber", "incidentId", "critical incident");
            JsonNode component = page.get("components").get(0);
            assertThat(component.get("history")).hasSize(90);
            assertThat(component.get("uptime90d").asDouble()).isBetween(0.0, 100.0);
            page.get("updates").forEach(u -> assertThat(u.has("incidentId")).isFalse());

            // Without SMTP a subscription cannot be confirmed, so nothing is stored; the answer stays generic.
            String address = "nosmtp-" + UUID.randomUUID().toString().substring(0, 6) + "@example.org";
            mvc.perform(jsonBody(post("/api/public/status/subscribe"), Map.of("email", address))).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.message").value(StatusSubscriptionService.SUBSCRIBE_MESSAGE));
            assertThat(jdbc.queryForObject("select count(*) from status_subscribers where email = ?", Long.class, address)).isZero();
            mvc.perform(jsonBody(post("/api/public/status/confirm"), Map.of("token", "bogus-token-0123456789abcdef"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(StatusSubscriptionService.CONFIRM_MESSAGE));
            mvc.perform(jsonBody(post("/api/public/status/unsubscribe"), Map.of("token", "x"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(StatusSubscriptionService.UNSUBSCRIBE_MESSAGE));
            mvc.perform(jsonBody(post("/api/public/status/subscribe"), Map.of("email", "not-an-email"))).andExpect(status().isBadRequest());
        } finally {
            publish(wasPublic);
            mvc.perform(as("sunita.joshi", delete("/api/status-page/components/" + internal[1]))).andExpect(status().isNoContent());
            mvc.perform(as("sunita.joshi", delete("/api/status-page/components/" + visible[1]))).andExpect(status().isNoContent());
        }
    }

    @Test
    void internalViewIsFilteredForEmployeesAndEditableByStatusPageEditorsOnly() throws Exception {
        long[] internal = serviceWithComponent(false);
        try {
            JsonNode staff = getJson("riya.varma", "/api/status-page");
            assertThat(staff.get("components").findValuesAsText("id")).contains(String.valueOf(internal[1]));
            assertThat(staff.get("subscribers").isNull()).isTrue(); // editors only
            assertThat(staff.get("emailConfigured").asBoolean()).isFalse();
            JsonNode employee = getJson("kavya.reddy", "/api/status-page");
            assertThat(employee.get("components").findValuesAsText("id")).doesNotContain(String.valueOf(internal[1]));
            employee.get("components").forEach(c -> assertThat(c.get("publicVisible").asBoolean()).isTrue());
            employee.get("updates").forEach(u -> assertThat(u.get("publicVisible").asBoolean()).isTrue());
            assertThat(getJson("sunita.joshi", "/api/status-page").at("/subscribers/confirmed").isNumber()).isTrue();

            Map<String, Object> update = Map.of("title", "Test update", "status", "INVESTIGATING", "message", "Looking into it");
            mvc.perform(jsonBody(as("riya.varma", post("/api/status-page/updates")), update)).andExpect(status().isForbidden());
            mvc.perform(jsonBody(as("priya.raman", post("/api/status-page/updates")), update)).andExpect(status().isForbidden());
            mvc.perform(jsonBody(as("kavya.reddy", put("/api/status-page/settings")), Map.of("title", "x", "publicEnabled", true)))
                    .andExpect(status().isForbidden());
            JsonNode posted = body(mvc.perform(jsonBody(as("sunita.joshi", post("/api/status-page/updates")), Map.of("title", "Internal note",
                    "status", "IDENTIFIED", "message", "Root cause found", "incidentId",
                    jdbc.queryForObject("select id from incidents order by id limit 1", Long.class)))).andExpect(status().isCreated()).andReturn());
            assertThat(posted.get("publicVisible").asBoolean()).isFalse();
            assertThat(posted.get("incidentNumber").asText()).startsWith("INC-");
            assertThat(getJson("kavya.reddy", "/api/status-page").get("updates").findValuesAsText("id")).doesNotContain(posted.get("id").asText());
            assertThat(getJson("riya.varma", "/api/status-page").get("updates").findValuesAsText("id")).contains(posted.get("id").asText());
            mvc.perform(jsonBody(as("rajesh.iyer", put("/api/status-page/updates/" + posted.get("id").asLong())), Map.of("title", "Internal note",
                    "status", "RESOLVED", "message", "Fixed"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
            mvc.perform(jsonBody(as("sunita.joshi", post("/api/status-page/components")), Map.of("serviceId", internal[0], "displayName", "Dup")))
                    .andExpect(status().isConflict());
        } finally {
            mvc.perform(as("sunita.joshi", delete("/api/status-page/components/" + internal[1]))).andExpect(status().isNoContent());
        }
    }

    @Test
    void maintenanceWindowsPutServicesIntoMaintenance() throws Exception {
        long[] sc = serviceWithComponent(true);
        try {
            Instant now = clock.instant();
            Map<String, Object> window = Map.of("title", "Patch window", "description", "Monthly patching", "startsAt", now.plus(Duration.ofHours(2)).toString(),
                    "endsAt", now.plus(Duration.ofHours(3)).toString(), "componentIds", List.of(sc[1]));
            mvc.perform(jsonBody(as("riya.varma", post("/api/status-page/maintenance")), window)).andExpect(status().isForbidden());
            mvc.perform(jsonBody(as("sunita.joshi", post("/api/status-page/maintenance")), Map.of("title", "Bad", "startsAt", now.toString(),
                    "endsAt", now.minusSeconds(60).toString(), "componentIds", List.of(sc[1])))).andExpect(status().isBadRequest());
            JsonNode created = body(mvc.perform(jsonBody(as("sunita.joshi", post("/api/status-page/maintenance")), window))
                    .andExpect(status().isCreated()).andReturn());
            long id = created.get("id").asLong();
            assertThat(created.get("status").asText()).isEqualTo("SCHEDULED");
            assertThat(serviceStatus(sc[0])).isNotEqualTo("MAINTENANCE");

            // In the window the service shows MAINTENANCE everywhere (services API, status page) with the window as the reason.
            testClock().advance(Duration.ofMinutes(150));
            JsonNode service = getJson("riya.varma", "/api/services/" + sc[0]);
            assertThat(service.get("status").asText()).isEqualTo("MAINTENANCE");
            assertThat(service.get("statusReason").asText()).isEqualTo("Planned maintenance: Patch window");
            JsonNode page = getJson("kavya.reddy", "/api/status-page");
            JsonNode component = page.get("components").findParents("id").stream().filter(c -> c.get("id").asLong() == sc[1]).findFirst().orElseThrow();
            assertThat(component.get("status").asText()).isEqualTo("MAINTENANCE");
            JsonNode listed = page.get("maintenance").findParents("title").stream().filter(m -> m.get("id").asLong() == id).findFirst().orElseThrow();
            assertThat(listed.get("status").asText()).isEqualTo("IN_PROGRESS");
            assertThat(listed.get("storedStatus").asText()).isEqualTo("SCHEDULED");

            // Completing it early ends the maintenance status; finished windows cannot be reopened.
            mvc.perform(jsonBody(as("sunita.joshi", post("/api/status-page/maintenance/" + id + "/status")), Map.of("status", "COMPLETED")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
            assertThat(serviceStatus(sc[0])).isNotEqualTo("MAINTENANCE");
            mvc.perform(jsonBody(as("sunita.joshi", post("/api/status-page/maintenance/" + id + "/status")), Map.of("status", "IN_PROGRESS")))
                    .andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("select count(*) from audit_logs where action in ('MAINTENANCE_SCHEDULED','MAINTENANCE_STATUS_CHANGED') "
                    + "and entity_id = ?", Long.class, String.valueOf(id))).isEqualTo(2);
        } finally {
            testClock().reset();
            mvc.perform(as("sunita.joshi", delete("/api/status-page/components/" + sc[1]))).andExpect(status().isNoContent());
        }
    }

    @Test
    void uptimeHistoryComesFromServiceAvailability() throws Exception {
        long[] sc = serviceWithComponent(true);
        try {
            long incident = createIncident("amit.shah", Map.of("title", unique("Status uptime check outage"), "description", "Full outage for the test",
                    "serviceId", sc[0], "impact", "HIGH", "urgency", "HIGH")).at("/incident/id").asLong();
            assertThat(jdbc.queryForObject("select priority from incidents where id = ?", String.class, incident)).isEqualTo("CRITICAL");
            jdbc.update("update incidents set created_at = now() - interval '6 hours' where id = ?", incident);
            JsonNode component = getJson("riya.varma", "/api/status-page").get("components").findParents("id").stream()
                    .filter(c -> c.get("id").asLong() == sc[1]).findFirst().orElseThrow();
            assertThat(component.get("status").asText()).isEqualTo("OUTAGE");
            JsonNode history = component.get("history");
            assertThat(history).hasSize(90);
            JsonNode serviceHistory = getJson("riya.varma", "/api/services/" + sc[0]).get("availabilityHistory");
            for (int i = 0; i < 90; i++) {
                assertThat(history.get(i).get("date").asText()).isEqualTo(serviceHistory.get(i).get("date").asText());
                // Today's value moves with the clock between the two calls; earlier days are identical.
                assertThat(history.get(i).get("availability").asDouble())
                        .isCloseTo(serviceHistory.get(i).get("availability").asDouble(), org.assertj.core.data.Offset.offset(i == 89 ? 0.5 : 0.0));
            }
            assertThat(history.get(89).get("availability").asDouble()).isLessThan(100.0);
            assertThat(component.get("uptime90d").asDouble()).isLessThan(100.0);
            mvc.perform(jsonBody(as("amit.shah", post("/api/incidents/" + incident + "/transition")), Map.of("status", "CLOSED",
                    "comment", "Test outage is over"))).andExpect(status().isOk());
        } finally {
            mvc.perform(as("sunita.joshi", delete("/api/status-page/components/" + sc[1]))).andExpect(status().isNoContent());
        }
    }

    private String serviceStatus(long serviceId) throws Exception {
        return getJson("riya.varma", "/api/services/" + serviceId).get("status").asText();
    }
}

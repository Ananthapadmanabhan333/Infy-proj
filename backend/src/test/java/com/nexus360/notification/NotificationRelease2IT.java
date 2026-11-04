package com.nexus360.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** Notification preferences (honoured when notifying) and the Server-Sent Events stream. */
class NotificationRelease2IT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NotificationStreamHub hub;

    private long count(String user, String type, long incidentId) {
        return jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id "
                + "where u.email = ? and n.type = ? and n.link = ?", Long.class, email(user), type, "/incidents/" + incidentId);
    }

    private void comment(long incident, String text) throws Exception {
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + incident + "/comments")), Map.of("body", text, "internal", false)))
                .andExpect(status().isCreated());
    }

    @Test
    void preferencesAreListedValidatedAndHonoured() throws Exception {
        JsonNode prefs = getJson("fatima.khan", "/api/notifications/preferences");
        // 11 Release 2 categories + ACCESS_REQUEST, PASSWORD_RESET_REQUEST (Release 2.1) + TEAM_QUEUE, REPORT_READY (Release 3)
        // + APPROVAL_ESCALATED, PROBLEM_UPDATED, CHANGE_UPDATED, DEPLOYMENT_STATUS (Release 3, BE-2)
        // + ALERT_ROUTED, ALERT_ESCALATED, ASSET_ASSIGNED (Release 3, BE-3) + ONCALL_PAGE, ONCALL_SHIFT, MAJOR_INCIDENT_UPDATE, PIR_UPDATED (BE-4)
        // + PREDICTION_DRIFT, AGENT_RUN (Release 4, BE-6)
        assertThat(prefs).hasSize(28);
        assertThat(prefs.findValuesAsText("type")).contains("INCIDENT_COMMENT", "SLA_AT_RISK", "WORKFLOW_FAILED", "ANNOUNCEMENT");
        assertThat(prefs.get(0).get("label").asText()).isNotBlank();
        assertThat(prefs.get(0).get("description").asText()).isNotBlank();

        mvc.perform(jsonBody(as("fatima.khan", put("/api/notifications/preferences")), Map.of("BOGUS", true)))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("fatima.khan", put("/api/notifications/preferences")), Map.of()))
                .andExpect(status().isBadRequest());

        long incident = createIncident("fatima.khan", Map.of("title", unique("Interview room screen share fails"),
                "description", "Cannot share the laptop screen")).at("/incident/id").asLong();
        try {
            comment(incident, "Please try the HDMI cable on the left.");
            assertThat(count("fatima.khan", "INCIDENT_COMMENT", incident)).isEqualTo(1);

            JsonNode updated = body(mvc.perform(jsonBody(as("fatima.khan", put("/api/notifications/preferences")),
                    Map.of("INCIDENT_COMMENT", false))).andExpect(status().isOk()).andReturn());
            for (JsonNode p : updated) {
                assertThat(p.get("inApp").asBoolean()).isEqualTo(!"INCIDENT_COMMENT".equals(p.get("type").asText()));
            }
            comment(incident, "Second update while notifications are off.");
            assertThat(count("fatima.khan", "INCIDENT_COMMENT", incident)).isEqualTo(1); // not notified
        } finally {
            mvc.perform(jsonBody(as("fatima.khan", put("/api/notifications/preferences")), Map.of("INCIDENT_COMMENT", true)))
                    .andExpect(status().isOk());
        }
        comment(incident, "Third update after notifications are back on.");
        assertThat(count("fatima.khan", "INCIDENT_COMMENT", incident)).isEqualTo(2);
    }

    @Test
    void theStreamPushesNotificationsAfterCommit() throws Exception {
        mvc.perform(get("/api/notifications/stream")).andExpect(status().isUnauthorized());

        MvcResult stream = mvc.perform(as("tanvi.desai", get("/api/notifications/stream")))
                .andExpect(request().asyncStarted()).andReturn();
        try {
            assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
            await(() -> content(stream).contains("event:ping"), "initial ping");
            long incident = createIncident("tanvi.desai", Map.of("title", unique("Payslip PDF opens blank"),
                    "description", "The payslip download is empty")).at("/incident/id").asLong();
            comment(incident, "We are looking at the payslip generator.");
            await(() -> content(stream).contains("event:notification") && content(stream).contains("/incidents/" + incident),
                    "notification event");
            assertThat(content(stream)).contains("\"type\":\"INCIDENT_COMMENT\"");
            assertThat(hub.openStreams(jdbc.queryForObject("select id from users where email = ?", Long.class, email("tanvi.desai"))))
                    .isPositive();
        } finally {
            hub.disconnect(jdbc.queryForObject("select id from users where email = ?", Long.class, email("tanvi.desai")));
        }
    }

    private static String content(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

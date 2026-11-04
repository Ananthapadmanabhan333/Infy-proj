package com.nexus360.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class IncidentLifecycleIT extends IntegrationTest {

    @Test
    void createClassifiesPrioritisesAssignsAndStartsSla() throws Exception {
        JsonNode result = createIncident("kavya.reddy", Map.of(
                "title", unique("VPN tunnel drops repeatedly when working from home"),
                "description", "The VPN disconnects every few minutes for me.", "affectedUsers", 1));
        JsonNode incident = result.get("incident");
        assertThat(result.at("/classification/serviceName").asText()).isEqualTo("VPN & Remote Access");
        assertThat(result.at("/classification/source").asText()).isEqualTo("RULES");
        assertThat(incident.get("category").asText()).isEqualTo("NETWORK");
        assertThat(incident.get("departmentName").asText()).isEqualTo("Network & Infrastructure");
        assertThat(incident.get("assigneeName").isNull()).isFalse();
        assertThat(incident.get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(incident.at("/sla/clockStatus").asText()).isEqualTo("RUNNING");
        assertThat(incident.get("number").asText()).startsWith("INC-");
    }

    @Test
    void validationErrorsAreReportedPerField() throws Exception {
        mvc.perform(jsonBody(as("kavya.reddy", post("/api/incidents")), Map.of("title", "abc", "affectedUsers", -1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItems("title", "affectedUsers")));
    }

    @Test
    void fullLifecycleWithPauseResolutionAndHistory() throws Exception {
        Map<String, Object> req = new HashMap<>(Map.of("title", unique("Quarterly zeta widget calibration fails"),
                "description", "Calibration tool aborts", "impact", "HIGH", "urgency", "HIGH"));
        long id = createIncident("riya.varma", req).at("/incident/id").asLong();

        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + id + "/transition")), Map.of("status", "IN_PROGRESS")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.firstResponseAt").isNotEmpty());

        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + id + "/transition")), Map.of("status", "PENDING")))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + id + "/transition")),
                        Map.of("status", "PENDING", "comment", "Waiting for the vendor patch")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sla.clockStatus").value("PAUSED"));

        testClock().advance(Duration.ofMinutes(90)); // paused time must not count
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + id + "/transition")), Map.of("status", "IN_PROGRESS")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sla.clockStatus").value("RUNNING"));
        JsonNode sla = getJson("riya.varma", "/api/incidents/" + id + "/sla");
        assertThat(sla.get("totalPausedSeconds").asLong()).isGreaterThanOrEqualTo(90 * 60 - 5);
        assertThat(sla.get("percentElapsed").asDouble()).isLessThan(5);

        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + id + "/transition")), Map.of("status", "CLOSED")))
                .andExpect(status().isConflict());
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + id + "/transition")),
                        Map.of("status", "RESOLVED", "resolutionCode", "FIXED", "resolutionNotes", "Applied the vendor patch to the tool.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.sla.outcome").value("MET"));

        JsonNode history = getJson("riya.varma", "/api/incidents/" + id + "/history");
        assertThat(history.findValuesAsText("eventType")).contains("CREATED", "STATUS_CHANGED");
        assertThat(history.findValuesAsText("newValue")).contains("IN_PROGRESS", "PENDING", "RESOLVED");
    }

    @Test
    void commentsRespectInternalVisibility() throws Exception {
        long id = createIncident("sneha.patil", Map.of("title", unique("Badge reader at gate 4 rejects card"), "description", "Card not accepted"))
                .at("/incident/id").asLong();
        mvc.perform(jsonBody(as("sneha.patil", post("/api/incidents/" + id + "/comments")), Map.of("body", "secret", "internal", true)))
                .andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("suresh.babu", post("/api/incidents/" + id + "/comments")), Map.of("body", "Checked the reader log", "internal", true)))
                .andExpect(status().isCreated());
        mvc.perform(jsonBody(as("suresh.babu", post("/api/incidents/" + id + "/comments")), Map.of("body", "Replacing the reader today", "internal", false)))
                .andExpect(status().isCreated());
        JsonNode employeeView = getJson("sneha.patil", "/api/incidents/" + id + "/comments");
        assertThat(employeeView.findValuesAsText("body")).contains("Replacing the reader today").doesNotContain("Checked the reader log");
        JsonNode staffView = getJson("suresh.babu", "/api/incidents/" + id + "/comments");
        assertThat(staffView.findValuesAsText("body")).contains("Checked the reader log");
    }

    @Test
    void attachmentsAreValidatedBySignature() throws Exception {
        long id = createIncident("fatima.khan", Map.of("title", unique("Monitor flickers on docking station"), "description", "Flicker"))
                .at("/incident/id").asLong();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        mvc.perform(as("fatima.khan", multipart("/api/incidents/" + id + "/attachments")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.contentType").value("image/png"));
        mvc.perform(as("fatima.khan", multipart("/api/incidents/" + id + "/attachments")
                        .file(new MockMultipartFile("file", "invoice.png", "image/png", "MZ fake exe".getBytes()))))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(as("fatima.khan", multipart("/api/incidents/" + id + "/attachments")
                        .file(new MockMultipartFile("file", "run.exe", "application/octet-stream", new byte[]{'M', 'Z'}))))
                .andExpect(status().isUnsupportedMediaType());
        JsonNode list = getJson("fatima.khan", "/api/incidents/" + id + "/attachments");
        assertThat(list).hasSize(1);
        mvc.perform(as("fatima.khan", get("/api/incidents/" + id + "/attachments/" + list.get(0).get("id").asLong() + "/download")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void manualAssignmentAndEscalation() throws Exception {
        long id = createIncident("tanvi.desai", Map.of("title", unique("Quarterly omega ledger export missing rows"),
                "description", "Rows missing")).at("/incident/id").asLong();
        JsonNode agents = getJson("priya.raman", "/api/users/assignable");
        long riya = 0;
        for (JsonNode a : agents) {
            if (a.get("fullName").asText().equals("Riya Varma")) {
                riya = a.get("id").asLong();
            }
        }
        mvc.perform(jsonBody(as("priya.raman", post("/api/incidents/" + id + "/assign")), Map.of("assigneeId", riya)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assigneeName").value("Riya Varma"))
                .andExpect(jsonPath("$.departmentName").value("Enterprise Applications"));
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + id + "/escalate")), Map.of("reason", "Finance close is blocked")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.escalationLevel").value(1));
        mvc.perform(jsonBody(as("tanvi.desai", post("/api/incidents/" + id + "/escalate")), Map.of("reason", "please")))
                .andExpect(status().isForbidden());
    }
}

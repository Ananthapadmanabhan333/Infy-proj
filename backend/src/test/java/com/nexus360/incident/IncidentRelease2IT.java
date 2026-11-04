package com.nexus360.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Release 2 lifecycle (NEW … REOPENED), cancellation, tab counts, activity timeline, escalations and related incidents. */
class IncidentRelease2IT extends IntegrationTest {

    private JsonNode transition(String user, long id, Map<String, Object> body, int expectedStatus) throws Exception {
        return body(mvc.perform(jsonBody(as(user, post("/api/incidents/" + id + "/transition")), body))
                .andExpect(status().is(expectedStatus)).andReturn());
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void newIncidentsAreAutoAssignedAndRequestersCanCancelWithAReason() throws Exception {
        JsonNode incident = createIncident("kavya.reddy", Map.of("title", unique("Keyboard keys stick on desk 12"),
                "description", "Several keys stay pressed"));
        long id = incident.at("/incident/id").asLong();
        assertThat(incident.at("/incident/status").asText()).isEqualTo("ASSIGNED");
        assertThat(texts(incident.at("/incident/allowedTransitions"))).containsExactly("CLOSED");

        transition("kavya.reddy", id, Map.of("status", "CLOSED"), 400); // a reason is required to cancel
        JsonNode cancelled = transition("kavya.reddy", id, Map.of("status", "CLOSED", "comment", "Fixed it myself after a restart"), 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CLOSED");
        assertThat(cancelled.get("resolutionCode").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("resolvedAt").isNull()).isFalse();
        assertThat(cancelled.at("/sla/outcome").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("allowedTransitions")).isEmpty();
        transition("riya.varma", id, Map.of("status", "RESOLVED", "resolutionCode", "FIXED", "resolutionNotes", "Too late to resolve"), 409);
        transition("riya.varma", id, Map.of("status", "IN_PROGRESS"), 409);
    }

    @Test
    void pendingPausesTheSlaAndARequesterCanReopenAResolvedIncident() throws Exception {
        long id = createIncident("manoj.verma", Map.of("title", unique("Learning portal course player stalls"),
                "description", "Course video stops", "impact", "MEDIUM", "urgency", "MEDIUM")).at("/incident/id").asLong();
        transition("riya.varma", id, Map.of("status", "IN_PROGRESS"), 200);
        transition("riya.varma", id, Map.of("status", "CLOSED", "comment", "Cancelling in progress work"), 409); // cancel only from NEW/ASSIGNED
        JsonNode pending = transition("riya.varma", id, Map.of("status", "PENDING", "comment", "Waiting for the content vendor"), 200);
        assertThat(pending.at("/sla/clockStatus").asText()).isEqualTo("PAUSED");
        assertThat(texts(pending.get("allowedTransitions"))).containsExactly("IN_PROGRESS", "RESOLVED");
        JsonNode resolved = transition("riya.varma", id, Map.of("status", "RESOLVED", "resolutionCode", "FIXED",
                "resolutionNotes", "Vendor fixed the video encoding."), 200);
        assertThat(resolved.at("/sla/clockStatus").asText()).isEqualTo("STOPPED");
        transition("riya.varma", id, Map.of("status", "RESOLVED", "resolutionCode", "CANCELLED", "resolutionNotes", "not allowed here"), 409);

        JsonNode employeeView = getJson("manoj.verma", "/api/incidents/" + id);
        assertThat(texts(employeeView.get("allowedTransitions"))).containsExactly("CLOSED", "REOPENED");
        JsonNode reopened = transition("manoj.verma", id, Map.of("status", "REOPENED", "comment", "Still stalls on chapter 3"), 200);
        assertThat(reopened.get("status").asText()).isEqualTo("REOPENED");
        assertThat(reopened.get("resolvedAt").isNull()).isTrue();
        assertThat(reopened.get("resolutionCode").isNull()).isTrue();
        assertThat(reopened.at("/sla/clockStatus").asText()).isEqualTo("RUNNING");
        assertThat(texts(getJson("riya.varma", "/api/incidents/" + id).get("allowedTransitions")))
                .containsExactly("ASSIGNED", "IN_PROGRESS", "PENDING", "RESOLVED");

        // Setting an assignee on a reopened incident moves it to ASSIGNED.
        long assignee = reopened.get("assigneeId").asLong();
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + id + "/assign")), Map.of("assigneeId", assignee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED"));
        JsonNode history = getJson("riya.varma", "/api/incidents/" + id + "/history");
        assertThat(history.findValuesAsText("newValue")).contains("PENDING", "RESOLVED", "REOPENED", "ASSIGNED");
    }

    @Test
    void legacyStatusNamesAreStillAcceptedInListFilters() throws Exception {
        mvc.perform(as("riya.varma", get("/api/incidents?status=OPEN,ON_HOLD&size=5"))).andExpect(status().isOk());
        mvc.perform(as("riya.varma", get("/api/incidents?status=BOGUS"))).andExpect(status().isBadRequest());
    }

    @Test
    void countsActivityEscalationsAndRelatedIncidents() throws Exception {
        long erp = 0;
        for (JsonNode s : getJson("riya.varma", "/api/services")) {
            if ("ERP".equals(s.get("code").asText())) {
                erp = s.get("id").asLong();
            }
        }
        String marker = "cnt" + UUID.randomUUID().toString().substring(0, 6);
        long first = createIncident("sanjay.menon", Map.of("title", "Ledger summary layout question " + marker, "description",
                "Column order changed", "serviceId", erp, "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();
        long second = createIncident("sanjay.menon", Map.of("title", "Vendor master filter request " + marker, "description",
                "Need an extra filter", "serviceId", erp, "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();

        JsonNode counts = getJson("riya.varma", "/api/incidents/counts?search=" + marker);
        assertThat(counts.get("all").asLong()).isEqualTo(2);
        assertThat(counts.get("open").asLong()).isEqualTo(2);
        assertThat(counts.get("inProgress").asLong()).isZero();
        transition("riya.varma", first, Map.of("status", "IN_PROGRESS"), 200);
        counts = getJson("riya.varma", "/api/incidents/counts?search=" + marker);
        assertThat(counts.get("inProgress").asLong()).isEqualTo(1);
        transition("riya.varma", first, Map.of("status", "RESOLVED", "resolutionCode", "FIXED", "resolutionNotes", "Explained the new layout."), 200);
        counts = getJson("riya.varma", "/api/incidents/counts?search=" + marker);
        assertThat(counts.get("resolved").asLong()).isEqualTo(1);
        assertThat(counts.get("open").asLong()).isEqualTo(1);
        assertThat(getJson("sanjay.menon", "/api/incidents/counts").get("all").asLong()).isPositive(); // employees: own incidents

        // HIGH so that it ranks among the five most urgent active incidents on the service.
        long third = createIncident("sanjay.menon", Map.of("title", "Cost centre hierarchy printout question " + marker, "description",
                "Hierarchy levels", "serviceId", erp, "impact", "HIGH", "urgency", "MEDIUM")).at("/incident/id").asLong();
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + second + "/escalate")), Map.of("reason", "Month-end close depends on it")))
                .andExpect(status().isOk());
        JsonNode detail = getJson("riya.varma", "/api/incidents/" + second);
        assertThat(detail.get("escalations")).hasSize(1);
        assertThat(detail.at("/escalations/0/level").asInt()).isEqualTo(1);
        assertThat(detail.at("/escalations/0/source").asText()).isEqualTo("MANUAL");
        assertThat(detail.at("/escalations/0/actorName").asText()).isEqualTo("Riya Varma");
        assertThat(detail.get("relatedSameService").findValuesAsText("id")).contains(String.valueOf(third))
                .doesNotContain(String.valueOf(second), String.valueOf(first));
        assertThat(detail.get("relatedSameService").size()).isLessThanOrEqualTo(5);

        JsonNode activity = getJson("riya.varma", "/api/incidents/" + second + "/activity");
        assertThat(activity.findValuesAsText("kind")).contains("HISTORY", "COMMENT", "SLA", "ESCALATION");
        assertThat(java.time.Instant.parse(activity.get(0).get("at").asText()))
                .isAfterOrEqualTo(java.time.Instant.parse(activity.get(activity.size() - 1).get("at").asText()));
        JsonNode employeeActivity = getJson("sanjay.menon", "/api/incidents/" + second + "/activity");
        assertThat(employeeActivity.findValuesAsText("kind")).doesNotContain("ESCALATION");
        assertThat(employeeActivity.findValuesAsText("title")).doesNotContain("Work note");
        JsonNode employeeDetail = getJson("sanjay.menon", "/api/incidents/" + second);
        assertThat(employeeDetail.get("escalations")).isEmpty();
        assertThat(employeeDetail.get("relatedSameService")).isEmpty();
        for (long id : new long[]{second, third}) {
            transition("riya.varma", id, Map.of("status", "RESOLVED", "resolutionCode", "FIXED", "resolutionNotes", "Answered the question."), 200);
        }
    }
}

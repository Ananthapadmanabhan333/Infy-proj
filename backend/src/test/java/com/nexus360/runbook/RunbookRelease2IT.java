package com.nexus360.runbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Runbooks Release 2: categories, prerequisites, sensitive steps approved by an incident manager, paginated history. */
class RunbookRelease2IT extends IntegrationTest {

    private JsonNode step(String user, long execution, int order, boolean done) throws Exception {
        return body(mvc.perform(jsonBody(as(user, post("/api/runbooks/executions/" + execution + "/steps/" + order)),
                Map.of("done", done, "notes", "step " + order))).andExpect(status().isOk()).andReturn());
    }

    private long start(String user, long runbook) throws Exception {
        JsonNode e = body(mvc.perform(jsonBody(as(user, post("/api/runbooks/" + runbook + "/executions")), Map.of()))
                .andExpect(status().isCreated()).andReturn());
        assertThat(e.get("status").asText()).isEqualTo("READY");
        return e.get("id").asLong();
    }

    @Test
    void sensitiveStepsNeedAnIncidentManager() throws Exception {
        String title = unique("Rotate the edge firewall certificate");
        Map<String, Object> request = Map.of("title", title, "description", "Certificate rotation on the edge firewall pair",
                "categories", List.of("NETWORK", "SECURITY"), "prerequisites", List.of("Change ticket approved", "Maintenance window open"),
                "steps", List.of(Map.of("order", 1, "title", "Export the current certificate", "instruction", "Back up the certificate"),
                        Map.of("order", 2, "title", "Install the new certificate", "instruction", "Upload and bind it", "requiresApproval", true)),
                "requiresApproval", false, "estimatedMinutes", 30, "active", true);
        mvc.perform(jsonBody(as("riya.varma", post("/api/runbooks")), request)).andExpect(status().isForbidden());
        JsonNode runbook = body(mvc.perform(jsonBody(as("kiran.rao", post("/api/runbooks")), request)).andExpect(status().isCreated()).andReturn());
        long id = runbook.get("id").asLong();
        assertThat(runbook.get("categories").toString()).contains("NETWORK", "SECURITY");
        assertThat(runbook.get("prerequisites")).hasSize(2);
        assertThat(runbook.at("/steps/1/requiresApproval").asBoolean()).isTrue();

        assertThat(getJson("arjun.kapoor", "/api/runbooks?category=SECURITY").findValuesAsText("title")).contains(title);
        assertThat(getJson("arjun.kapoor", "/api/runbooks?category=FACILITIES").findValuesAsText("title")).doesNotContain(title);

        // An agent asks for approval of the sensitive step; the runbook cannot be completed until it is decided.
        long first = start("arjun.kapoor", id);
        step("arjun.kapoor", first, 1, true);
        JsonNode requested = step("arjun.kapoor", first, 2, true);
        JsonNode sensitive = requested.at("/stepStates/1");
        assertThat(sensitive.get("done").asBoolean()).isFalse();
        assertThat(sensitive.get("approvalStatus").asText()).isEqualTo("PENDING");
        long approvalId = sensitive.get("approvalId").asLong();
        mvc.perform(jsonBody(as("arjun.kapoor", post("/api/runbooks/executions/" + first + "/steps/2")), Map.of("done", true)))
                .andExpect(status().isConflict());
        mvc.perform(jsonBody(as("arjun.kapoor", post("/api/runbooks/executions/" + first + "/complete")), Map.of()))
                .andExpect(status().isConflict());

        JsonNode pending = getJson("sunita.joshi", "/api/approvals?status=PENDING&size=100").get("content");
        JsonNode mine = null;
        for (JsonNode a : pending) {
            if (a.get("id").asLong() == approvalId) {
                mine = a;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("type").asText()).isEqualTo("RUNBOOK_STEP");
        assertThat(mine.get("approverRole").asText()).isEqualTo("INCIDENT_MANAGER");
        mvc.perform(jsonBody(as("kiran.rao", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", true)))
                .andExpect(status().isForbidden()); // routed to incident managers
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", true, "comment", "Window confirmed")))
                .andExpect(status().isOk());
        JsonNode afterApproval = getJson("arjun.kapoor", "/api/runbooks/executions?runbookId=" + id).at("/content/0");
        assertThat(afterApproval.at("/stepStates/1/done").asBoolean()).isTrue();
        assertThat(afterApproval.at("/stepStates/1/approvalStatus").asText()).isEqualTo("APPROVED");
        JsonNode completed = body(mvc.perform(jsonBody(as("arjun.kapoor", post("/api/runbooks/executions/" + first + "/complete")),
                Map.of("outcomeNotes", "Rotated"))).andExpect(status().isOk()).andReturn());
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");

        // A rejected request leaves the step open.
        long second = start("vikram.singh", id);
        long rejectedId = step("vikram.singh", second, 2, true).at("/stepStates/1/approvalId").asLong();
        mvc.perform(jsonBody(as("admin", post("/api/approvals/" + rejectedId + "/decision")), Map.of("approved", false, "comment", "Not tonight")))
                .andExpect(status().isOk());
        JsonNode rejected = getJson("vikram.singh", "/api/runbooks/executions?runbookId=" + id + "&size=1").at("/content/0");
        assertThat(rejected.get("id").asLong()).isEqualTo(second);
        assertThat(rejected.at("/stepStates/1/done").asBoolean()).isFalse();
        assertThat(rejected.at("/stepStates/1/approvalStatus").asText()).isEqualTo("REJECTED");

        // An incident manager completes the sensitive step directly.
        long third = start("sunita.joshi", id);
        JsonNode direct = step("sunita.joshi", third, 2, true).at("/stepStates/1");
        assertThat(direct.get("done").asBoolean()).isTrue();
        assertThat(direct.get("approvalStatus").asText()).isEqualTo("APPROVED");
        assertThat(direct.get("completedByName").asText()).isEqualTo("Sunita Joshi");

        JsonNode page = getJson("arjun.kapoor", "/api/runbooks/executions?runbookId=" + id + "&size=2&page=0");
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(getJson("arjun.kapoor", "/api/runbooks/executions?runbookId=" + id + "&status=COMPLETED").get("totalElements").asLong())
                .isEqualTo(1);
        mvc.perform(as("kavya.reddy", org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/runbooks/executions")))
                .andExpect(status().isForbidden());
    }
}

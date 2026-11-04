package com.nexus360.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiAndKnowledgeIT extends IntegrationTest {

    @Test
    void withoutAProviderEveryFeatureAnswersFromTheLabelledFallback() throws Exception {
        assertThat(getJson("riya.varma", "/api/ai/status").get("llmConfigured").asBoolean()).isFalse();
        long id = createIncident("rohan.kulkarni", Map.of("title", unique("Payslip download fails with error 503"),
                "description", "Payroll portal shows 503 when downloading payslips")).at("/incident/id").asLong();
        for (String feature : new String[]{"classify", "summarize", "root-cause", "knowledge", "duplicates"}) {
            JsonNode r = body(mvc.perform(as("riya.varma", post("/api/ai/incidents/" + id + "/" + feature))).andExpect(status().isOk()).andReturn());
            assertThat(r.get("source").asText()).as(feature).isEqualTo("FALLBACK");
            assertThat(r.get("notice").asText()).as(feature).contains("No LLM was used");
            assertThat(r.get("model").isNull()).as(feature).isTrue();
        }
        JsonNode ask = body(mvc.perform(jsonBody(as("priya.raman", post("/api/ai/ask")),
                Map.of("question", "How many critical incidents were reported this week?"))).andExpect(status().isOk()).andReturn());
        assertThat(ask.at("/data/answer").asText()).contains("reported in the last 7 days");
        assertThat(ask.at("/data/basis").asText()).isNotBlank();
        mvc.perform(as("priya.raman", post("/api/ai/insights"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insights[0].title").isNotEmpty());
    }

    @Test
    void sensitiveAiSuggestionsNeedIncidentManagerApproval() throws Exception {
        long id = createIncident("rohan.kulkarni", Map.of("title", unique("Expense approval queue frozen"),
                "description", "Approvals stuck", "impact", "LOW", "urgency", "LOW")).at("/incident/id").asLong();
        JsonNode approval = body(mvc.perform(jsonBody(as("riya.varma", post("/api/ai/actions")), Map.of("incidentId", id,
                "actionType", "SET_PRIORITY", "params", Map.of("priority", "CRITICAL"), "rationale", "Finance close blocked")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(approval.get("status").asText()).isEqualTo("PENDING");
        assertThat(getJson("riya.varma", "/api/incidents/" + id).get("priority").asText()).isEqualTo("LOW"); // nothing applied yet

        long approvalId = approval.get("id").asLong();
        assertThat(approval.get("approverRole").asText()).isEqualTo("INCIDENT_MANAGER");
        mvc.perform(jsonBody(as("riya.varma", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", true)))
                .andExpect(status().isForbidden()); // agents cannot approve
        mvc.perform(jsonBody(as("priya.raman", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", true, "comment", "Agreed")))
                .andExpect(status().isForbidden()); // routed to incident managers, not IT managers
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", true, "comment", "Agreed")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(getJson("riya.varma", "/api/incidents/" + id).get("priority").asText()).isEqualTo("CRITICAL");
    }

    @Test
    void knowledgeSearchRanksByRelevanceAndHidesDrafts() throws Exception {
        JsonNode results = getJson("amit.shah", "/api/knowledge?q=payroll%20503");
        assertThat(results.at("/content/0/title").asText()).isEqualTo("Payroll application troubleshooting guide");
        JsonNode typo = getJson("amit.shah", "/api/knowledge?q=vpn%20slw");
        assertThat(typo.get("content").findValuesAsText("title")).contains("VPN connection troubleshooting");

        JsonNode draft = body(mvc.perform(jsonBody(as("riya.varma", post("/api/knowledge")), Map.of("title", "Draft: payroll cache tuning notes",
                "summary", "Internal draft about payroll cache tuning", "content", "Work in progress notes about payroll cache tuning.",
                "articleType", "GUIDE", "category", "APPLICATION", "tags", java.util.List.of("payroll"), "status", "DRAFT")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(getJson("riya.varma", "/api/knowledge?q=cache%20tuning").get("content").findValuesAsText("number"))
                .contains(draft.get("number").asText());
        assertThat(getJson("amit.shah", "/api/knowledge?q=cache%20tuning").get("content").findValuesAsText("number"))
                .doesNotContain(draft.get("number").asText());
        mvc.perform(jsonBody(as("amit.shah", post("/api/knowledge")), Map.of("title", "x"))).andExpect(status().is4xxClientError());
    }

    @Test
    void serviceRequestApprovalFlow() throws Exception {
        JsonNode items = getJson("kavya.reddy", "/api/catalog/items");
        long vpnItem = 0;
        for (JsonNode i : items) {
            if (i.get("name").asText().equals("VPN access")) {
                vpnItem = i.get("id").asLong();
            }
        }
        mvc.perform(jsonBody(as("kavya.reddy", post("/api/service-requests")), Map.of("catalogItemId", vpnItem, "title", "VPN for offsite",
                "formData", Map.of()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("'Needed from' is required"));
        JsonNode request = body(mvc.perform(jsonBody(as("kavya.reddy", post("/api/service-requests")), Map.of("catalogItemId", vpnItem,
                "title", "VPN for offsite workshop", "formData", Map.of("startDate", "2026-12-01", "justification", "Workshop"))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(request.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        long approvalId = request.at("/approval/id").asLong();
        mvc.perform(jsonBody(as("kiran.rao", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", false)))
                .andExpect(status().isBadRequest()); // rejection needs a reason
        mvc.perform(jsonBody(as("kiran.rao", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", true, "comment", "ok")))
                .andExpect(status().isOk());
        JsonNode after = getJson("kavya.reddy", "/api/service-requests/" + request.get("id").asLong());
        assertThat(after.get("status").asText()).isEqualTo("APPROVED");
        assertThat(after.get("dueAt").isNull()).isFalse();
        assertThat(after.get("assigneeName").isNull()).isFalse();
    }
}

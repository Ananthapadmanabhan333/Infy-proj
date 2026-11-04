package com.nexus360.servicedesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceDeskApprovalIT extends IntegrationTest {

    private long vpnItemId() throws Exception {
        JsonNode items = getJson("kavya.reddy", "/api/catalog/items?search=VPN");
        for (JsonNode item : items) {
            if (item.get("name").asText().equals("VPN access")) return item.get("id").asLong();
        }
        throw new AssertionError("VPN access catalogue item is seeded");
    }

    private JsonNode request(String user, long itemId) throws Exception {
        return body(mvc.perform(jsonBody(as(user, post("/api/service-requests")), Map.of(
                        "catalogItemId", itemId,
                        "title", unique("VPN for client visit"),
                        "formData", Map.of("startDate", "2026-11-20", "justification", "Working from the client site for two weeks"))))
                .andExpect(status().isCreated()).andReturn());
    }

    private long pendingApprovalFor(String manager, String referenceNumber) throws Exception {
        for (JsonNode a : getJson(manager, "/api/approvals?status=PENDING&size=100").get("content")) {
            if (referenceNumber.equals(a.get("referenceNumber").asText())) return a.get("id").asLong();
        }
        throw new AssertionError("approval for " + referenceNumber + " is routed to " + manager);
    }

    @Test
    void requiredFormFieldsAreValidatedOnTheServer() throws Exception {
        mvc.perform(jsonBody(as("kavya.reddy", post("/api/service-requests")), Map.of(
                        "catalogItemId", vpnItemId(), "title", "VPN please", "formData", Map.of("justification", "Travel"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approvalGatedRequestIsApprovedByAManagerAndStartsFulfilment() throws Exception {
        JsonNode created = request("kavya.reddy", vpnItemId());
        assertThat(created.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(created.get("dueAt").isNull()).isTrue();
        String number = created.get("number").asText();

        mvc.perform(as("arjun.kapoor", get("/api/approvals?status=PENDING"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.referenceNumber == '" + number + "')]").isEmpty()); // not routed to agents
        mvc.perform(jsonBody(as("kavya.reddy", post("/api/approvals/1/decision")), Map.of("approved", true)))
                .andExpect(status().isForbidden());

        long approvalId = pendingApprovalFor("kiran.rao", number);
        mvc.perform(jsonBody(as("kiran.rao", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", false)))
                .andExpect(status().isBadRequest()); // a rejection needs a reason
        mvc.perform(jsonBody(as("kiran.rao", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", true, "comment", "OK")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(jsonBody(as("kiran.rao", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", true)))
                .andExpect(status().isConflict());

        JsonNode approved = getJson("kavya.reddy", "/api/service-requests/" + created.get("id").asLong());
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("dueAt").isNull()).isFalse();
        assertThat(approved.get("assigneeName").isNull()).isFalse();
        assertThat(getJson("kavya.reddy", "/api/notifications?size=20").at("/content").findValuesAsText("type")).contains("APPROVAL_DECIDED");
    }

    @Test
    void managersCannotApproveTheirOwnRequests() throws Exception {
        JsonNode own = request("priya.raman", vpnItemId());
        long approvalId = pendingApprovalFor("priya.raman", own.get("number").asText());
        mvc.perform(jsonBody(as("priya.raman", post("/api/approvals/" + approvalId + "/decision")), Map.of("approved", true, "comment", "self")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Segregation of duties: you cannot approve your own request"));
    }
}

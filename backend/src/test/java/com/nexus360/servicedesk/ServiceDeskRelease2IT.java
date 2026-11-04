package com.nexus360.servicedesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Service desk Release 2: categories, request priority, comments with visibility, history, filters and tab counts. */
class ServiceDeskRelease2IT extends IntegrationTest {

    private long itemId(String name) throws Exception {
        for (JsonNode item : getJson("kavya.reddy", "/api/catalog/items")) {
            if (item.get("name").asText().equals(name)) {
                return item.get("id").asLong();
            }
        }
        throw new AssertionError(name + " is seeded");
    }

    private Map<String, Object> softwareRequest(String title, String priority) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("catalogItemId", itemId("Software installation"), "title", title,
                "formData", Map.of("software", "Power BI Desktop")));
        if (priority != null) {
            body.put("priority", priority);
        }
        return body;
    }

    @Test
    void standardCategoriesAreSeededAndItemsRemapped() throws Exception {
        JsonNode categories = getJson("kavya.reddy", "/api/catalog/categories");
        assertThat(categories.findValuesAsText("code")).containsExactly("HARDWARE", "SOFTWARE", "NETWORK", "ACCESS", "ACCOUNT", "FACILITIES", "GENERAL");
        assertThat(categories.findValuesAsText("name")).contains("Access Management", "General");
        for (JsonNode item : getJson("kavya.reddy", "/api/catalog/items")) {
            if (item.get("name").asText().equals("VPN access")) {
                assertThat(item.get("categoryName").asText()).isEqualTo("Network");
                assertThat(item.get("approverRole").asText()).isEqualTo("IT_MANAGER");
            }
        }
    }

    @Test
    void priorityCommentsHistoryFiltersAndCounts() throws Exception {
        String marker = "sd" + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(jsonBody(as("kavya.reddy", post("/api/service-requests")), softwareRequest("Install tool " + marker, "CRITICAL")))
                .andExpect(status().isBadRequest()); // employees choose LOW to HIGH
        JsonNode created = body(mvc.perform(jsonBody(as("kavya.reddy", post("/api/service-requests")),
                softwareRequest("Install analytics tool " + marker, "HIGH"))).andExpect(status().isCreated()).andReturn());
        long id = created.get("id").asLong();
        assertThat(created.get("priority").asText()).isEqualTo("HIGH");
        assertThat(created.get("categoryCode").asText()).isEqualTo("SOFTWARE");
        assertThat(created.get("commentCount").asLong()).isZero();
        JsonNode defaulted = body(mvc.perform(jsonBody(as("kavya.reddy", post("/api/service-requests")),
                softwareRequest("Install viewer " + marker, null))).andExpect(status().isCreated()).andReturn());
        assertThat(defaulted.get("priority").asText()).isEqualTo("MEDIUM");
        mvc.perform(jsonBody(as("riya.varma", post("/api/service-requests")), softwareRequest("Install profiler " + marker, "CRITICAL")))
                .andExpect(status().isCreated()); // staff may choose any priority

        String base = "/api/service-requests/" + id;
        mvc.perform(jsonBody(as("kavya.reddy", post(base + "/comments")), Map.of("body", "secret", "internal", true)))
                .andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("kavya.reddy", post(base + "/comments")), Map.of("body", "Needed for the quarterly review", "internal", false)))
                .andExpect(status().isCreated());
        mvc.perform(jsonBody(as("vishal.kumar", post(base + "/comments")), Map.of("body", "Licence check pending", "internal", true)))
                .andExpect(status().isCreated());
        mvc.perform(jsonBody(as("vishal.kumar", post(base + "/comments")), Map.of("body", "Installing this afternoon", "internal", false)))
                .andExpect(status().isCreated());
        mvc.perform(jsonBody(as("ananya.bose", post(base + "/comments")), Map.of("body", "auditor", "internal", false)))
                .andExpect(status().isForbidden());
        assertThat(getJson("kavya.reddy", base + "/comments").findValuesAsText("body"))
                .containsExactly("Needed for the quarterly review", "Installing this afternoon");
        assertThat(getJson("vishal.kumar", base + "/comments").findValuesAsText("body")).contains("Licence check pending");
        assertThat(getJson("kavya.reddy", base).get("commentCount").asLong()).isEqualTo(2);
        assertThat(getJson("vishal.kumar", base).get("commentCount").asLong()).isEqualTo(3);
        assertThat(getJson("kavya.reddy", "/api/notifications?size=30").at("/content").findValuesAsText("type")).contains("REQUEST_COMMENT");

        mvc.perform(as("vishal.kumar", post(base + "/start"))).andExpect(status().isOk());
        JsonNode history = getJson("kavya.reddy", base + "/history");
        assertThat(history.findValuesAsText("action")).contains("CREATED", "COMMENTED", "STATUS_CHANGED");
        assertThat(history.findValuesAsText("toValue")).contains("IN_FULFILLMENT");
        assertThat(history.get(0).get("actorName").asText()).isEqualTo("Kavya Reddy");

        JsonNode filtered = getJson("kavya.reddy", "/api/service-requests?category=SOFTWARE&priority=HIGH&search=" + marker);
        assertThat(filtered.get("content").findValuesAsText("id")).containsExactly(String.valueOf(id));
        assertThat(getJson("kavya.reddy", "/api/service-requests?priority=LOW&search=" + marker).get("totalElements").asLong()).isZero();
        assertThat(getJson("kavya.reddy", "/api/service-requests?category=NETWORK&search=" + marker).get("totalElements").asLong()).isZero();

        JsonNode counts = getJson("kavya.reddy", "/api/service-requests/counts?search=" + marker);
        assertThat(counts.get("all").asLong()).isEqualTo(2); // employees only see their own requests
        assertThat(counts.get("mine").asLong()).isEqualTo(2);
        assertThat(counts.get("inFulfillment").asLong()).isEqualTo(2); // one approved, one being fulfilled
        assertThat(counts.get("pendingApproval").asLong()).isZero();
        JsonNode staffCounts = getJson("vishal.kumar", "/api/service-requests/counts?scope=all&search=" + marker);
        assertThat(staffCounts.get("all").asLong()).isEqualTo(3);
        assertThat(staffCounts.get("mine").asLong()).isZero();

        mvc.perform(jsonBody(as("vishal.kumar", post(base + "/fulfill")), Map.of("notes", "Installed"))).andExpect(status().isOk());
        assertThat(getJson("kavya.reddy", "/api/service-requests/counts?search=" + marker).get("fulfilled").asLong()).isEqualTo(1);
        mvc.perform(as("amit.shah", get(base + "/history"))).andExpect(status().isNotFound());
    }
}

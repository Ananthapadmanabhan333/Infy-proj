package com.nexus360.mywork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** My Work (staff) and the employee portal overview, including employee data isolation (docs/RELEASE3_SPEC.md §4). */
class MyWorkAndPortalIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    List<MyWorkContributor> contributors;

    private long userId(String user) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email(user));
    }

    private long vpnItem() {
        return jdbc.queryForObject("select id from catalog_items where name = 'VPN access'", Long.class);
    }

    @Test
    void myWorkListsAssignedWorkTeamQueuesAndApprovals() throws Exception {
        long iam = jdbc.queryForObject("select id from business_services where code = 'IAM'", Long.class);
        JsonNode incident = createIncident("rohan.kulkarni", Map.of("title", unique("Hardware token shows wrong time drift"),
                "description", "The hardware token code is rejected due to time drift", "serviceId", iam)).get("incident");
        long id = incident.get("id").asLong();
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + id + "/assign")), Map.of("assigneeId", userId("lakshmi.menon"))))
                .andExpect(status().isOk());

        JsonNode work = getJson("lakshmi.menon", "/api/my-work?limit=50");
        List<String> keys = new ArrayList<>();
        work.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsSubsequence("generatedAt", "teams", "incidents", "teamQueue", "requests", "teamRequests", "approvals");
        assertThat(work.get("teams").findValuesAsText("code")).contains("ID-SEC");
        assertThat(work.at("/incidents/total").asLong()).isPositive();
        assertThat(work.at("/incidents/items").findValuesAsText("number")).contains(incident.get("number").asText());
        work.at("/incidents/items").forEach(i -> assertThat(i.get("assigneeName").asText()).isEqualTo("Lakshmi Menon"));
        assertThat(work.at("/approvals/total").asLong()).isZero(); // agents decide no approvals

        // Unassigned work of my teams.
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + id + "/assign")),
                Map.of("teamId", jdbc.queryForObject("select id from teams where code = 'ID-SEC'", Long.class)))).andExpect(status().isOk());
        JsonNode queue = getJson("lakshmi.menon", "/api/my-work?limit=50");
        assertThat(queue.at("/teamQueue/items").findValuesAsText("number")).contains(incident.get("number").asText());
        assertThat(queue.at("/incidents/items").findValuesAsText("number")).doesNotContain(incident.get("number").asText());

        // Approvers see what they may decide, never their own requests.
        JsonNode request = body(mvc.perform(jsonBody(as("kavya.reddy", post("/api/service-requests")), Map.of("catalogItemId", vpnItem(),
                "title", unique("VPN for the audit week"), "formData", Map.of("startDate", "2026-12-07", "justification", "Audit week"))))
                .andExpect(status().isCreated()).andReturn());
        JsonNode managerWork = getJson("priya.raman", "/api/my-work?limit=50");
        assertThat(managerWork.at("/approvals/items").findValuesAsText("referenceNumber")).contains(request.get("number").asText());
        JsonNode own = body(mvc.perform(jsonBody(as("priya.raman", post("/api/service-requests")), Map.of("catalogItemId", vpnItem(),
                "title", unique("VPN for the manager"), "formData", Map.of("startDate", "2026-12-07", "justification", "Travel"))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(getJson("priya.raman", "/api/my-work?limit=50").at("/approvals/items").findValuesAsText("referenceNumber"))
                .doesNotContain(own.get("number").asText());

        // Staff only.
        mvc.perform(as("kavya.reddy", get("/api/my-work"))).andExpect(status().isForbidden());
        mvc.perform(as("ananya.bose", get("/api/my-work"))).andExpect(status().isForbidden());
        assertThat(contributors.stream().map(MyWorkContributor::key)).contains("incidents", "teamQueue", "requests", "teamRequests", "approvals");
    }

    @Test
    void portalShowsOnlyTheCallersOwnRecordsAndPublicInformation() throws Exception {
        JsonNode mine = createIncident("ishaan.malhotra", Map.of("title", unique("Vendor remittance PDF renders blank pages"),
                "description", "Remittance advice PDFs have blank pages"));
        createIncident("sneha.patil", Map.of("title", unique("Appraisal form autosave lost my draft"),
                "description", "The appraisal draft disappeared after autosave"));
        JsonNode request = body(mvc.perform(jsonBody(as("ishaan.malhotra", post("/api/service-requests")), Map.of("catalogItemId", vpnItem(),
                "title", unique("VPN for supplier visit"), "formData", Map.of("startDate", "2026-12-14", "justification", "Supplier visit"))))
                .andExpect(status().isCreated()).andReturn());

        JsonNode portal = getJson("ishaan.malhotra", "/api/portal/overview");
        List<String> keys = new ArrayList<>();
        portal.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsSubsequence("generatedAt", "openIncidents", "openRequests", "awaitingApproval", "serviceStatus", "recommendedArticles");
        long me = userId("ishaan.malhotra");
        assertThat(portal.at("/openIncidents/items").findValuesAsText("number")).contains(mine.at("/incident/number").asText());
        portal.at("/openIncidents/items").forEach(i -> assertThat(i.get("requesterId").asLong()).isEqualTo(me));
        assertThat(portal.at("/openIncidents/total").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from incidents where "
                + "(requester_id = ? or created_by_id = ?) and status in ('NEW','ASSIGNED','IN_PROGRESS','PENDING','REOPENED')", Long.class, me, me));
        assertThat(portal.at("/openRequests/items").findValuesAsText("number")).contains(request.get("number").asText());
        portal.at("/openRequests/items").forEach(r -> assertThat(r.get("requesterId").asLong()).isEqualTo(me));
        assertThat(portal.at("/awaitingApproval/items").findValuesAsText("referenceNumber")).contains(request.get("number").asText());
        assertThat(portal.at("/awaitingApproval/items/0/approverRole").asText()).isEqualTo("IT_MANAGER");
        assertThat(portal.at("/serviceStatus/services").size()).isGreaterThanOrEqualTo(10);
        assertThat(portal.at("/serviceStatus/overall").asText()).isIn("OPERATIONAL", "DEGRADED", "OUTAGE", "MAINTENANCE");
        assertThat(portal.at("/serviceStatus/counts/OPERATIONAL").isNumber()).isTrue();
        portal.get("recommendedArticles").forEach(a -> assertThat(a.get("status").asText()).isEqualTo("PUBLISHED"));
        assertThat(portal.get("recommendedArticles").size()).isBetween(1, 5);

        // Another employee sees none of it.
        JsonNode other = getJson("sneha.patil", "/api/portal/overview");
        assertThat(other.at("/openIncidents/items").findValuesAsText("number")).doesNotContain(mine.at("/incident/number").asText());
        assertThat(other.at("/openRequests/items").findValuesAsText("number")).doesNotContain(request.get("number").asText());
        assertThat(other.at("/awaitingApproval/items").findValuesAsText("referenceNumber")).doesNotContain(request.get("number").asText());
        long sneha = userId("sneha.patil");
        other.at("/openIncidents/items").forEach(i -> assertThat(i.get("requesterId").asLong()).isEqualTo(sneha));
        // Staff get their own portal too (only what they raised).
        JsonNode staff = getJson("riya.varma", "/api/portal/overview");
        assertThat(staff.at("/openIncidents/items").findValuesAsText("number")).doesNotContain(mine.at("/incident/number").asText());
    }
}

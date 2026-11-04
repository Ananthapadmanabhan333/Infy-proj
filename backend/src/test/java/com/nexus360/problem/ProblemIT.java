package com.nexus360.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Problem management: lifecycle rules, known error to knowledge base, corrective actions, linking, recurring detection, AI. */
class ProblemIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long service(String code) {
        return jdbc.queryForObject("select id from business_services where code = ?", Long.class, code);
    }

    private long userId(String user) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email(user));
    }

    private long incident(String title, String serviceCode) throws Exception {
        return createIncident("kavya.reddy", Map.of("title", title, "description", title + " since this morning", "serviceId", service(serviceCode)))
                .at("/incident/id").asLong();
    }

    private JsonNode create(String user, Map<String, Object> body) throws Exception {
        return body(mvc.perform(jsonBody(as(user, post("/api/problems")), body)).andExpect(status().isCreated()).andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions transition(String user, long id, Map<String, Object> body) throws Exception {
        return mvc.perform(jsonBody(as(user, post("/api/problems/" + id + "/transition")), body));
    }

    @Test
    void lifecycleIsEnforcedAndKnownErrorsReachTheKnowledgeBase() throws Exception {
        long inc = incident(unique("Expense portal times out on submit"), "ERP");
        JsonNode p = create("riya.varma", Map.of("title", unique("Expense portal timeouts"), "priority", "HIGH", "serviceIds", List.of(service("ERP")),
                "incidentIds", List.of(inc), "ownerId", userId("riya.varma")));
        long id = p.get("id").asLong();
        assertThat(p.get("number").asText()).startsWith("PRB-");
        assertThat(p.get("status").asText()).isEqualTo("NEW");
        assertThat(p.get("incidents").findValuesAsText("id")).contains(String.valueOf(inc));

        transition("riya.varma", id, Map.of("status", "ROOT_CAUSE_IDENTIFIED")).andExpect(status().isConflict());
        transition("riya.varma", id, Map.of("status", "INVESTIGATING")).andExpect(status().isOk());
        transition("riya.varma", id, Map.of("status", "ROOT_CAUSE_IDENTIFIED")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("rootCause"));
        transition("riya.varma", id, Map.of("status", "ROOT_CAUSE_IDENTIFIED", "rootCause", "ERP session cache limited to 200 entries"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rootCauseAt").isNotEmpty());
        transition("riya.varma", id, Map.of("status", "KNOWN_ERROR")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("workaround"));
        JsonNode known = body(transition("riya.varma", id, Map.of("status", "KNOWN_ERROR", "workaround", "Submit expenses in batches of ten",
                "publishKnownError", true)).andExpect(status().isOk()).andReturn());
        assertThat(known.get("knownError").asBoolean()).isTrue();
        assertThat(known.get("kbArticleStatus").asText()).isEqualTo("IN_REVIEW"); // agents submit for review
        JsonNode article = getJson("riya.varma", "/api/knowledge/" + known.get("kbArticleId").asLong());
        assertThat(article.get("articleType").asText()).isEqualTo("KNOWN_ERROR");
        assertThat(article.get("content").asText()).contains("Submit expenses in batches of ten");
        mvc.perform(as("riya.varma", post("/api/problems/" + id + "/known-error-article"))).andExpect(status().isConflict());

        JsonNode action = body(mvc.perform(jsonBody(as("riya.varma", post("/api/problems/" + id + "/actions")), Map.of("title", "Raise the cache limit",
                "ownerId", userId("dev.nair"), "dueDate", LocalDate.now().plusDays(5).toString()))).andExpect(status().isCreated()).andReturn());
        assertThat(getJson("dev.nair", "/api/notifications?size=20").at("/content").findValuesAsText("type")).contains("PROBLEM_ACTION_ASSIGNED");
        transition("riya.varma", id, Map.of("status", "RESOLVED")).andExpect(status().isBadRequest());
        transition("riya.varma", id, Map.of("status", "RESOLVED", "permanentFix", "Cache limit raised to 2,000 entries")).andExpect(status().isOk());
        transition("riya.varma", id, Map.of("status", "CLOSED")).andExpect(status().isForbidden()); // admins and managers close
        transition("priya.raman", id, Map.of("status", "CLOSED")).andExpect(status().isConflict()); // open action
        mvc.perform(jsonBody(as("dev.nair", put("/api/problems/" + id + "/actions/" + action.get("id").asLong())), Map.of("title", "Raise the cache limit",
                "status", "DONE"))).andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").isNotEmpty());
        transition("priya.raman", id, Map.of("status", "CLOSED", "note", "Verified")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(jsonBody(as("riya.varma", put("/api/problems/" + id)), Map.of("title", "Too late to edit"))).andExpect(status().isConflict());
        assertThat(getJson("ananya.bose", "/api/problems/" + id + "/history").findValuesAsText("toValue"))
                .contains("INVESTIGATING", "ROOT_CAUSE_IDENTIFIED", "KNOWN_ERROR", "RESOLVED", "CLOSED");
    }

    @Test
    void managersPublishKnownErrorsAndPermissionsFollowTheRoleGroups() throws Exception {
        JsonNode p = create("priya.raman", Map.of("title", unique("Printer queue stalls"), "serviceIds", List.of(service("EUC")),
                "rootCause", "Spooler memory leak", "workaround", "Restart the spooler service"));
        long id = p.get("id").asLong();
        mvc.perform(as("priya.raman", post("/api/problems/" + id + "/known-error-article"))).andExpect(status().isConflict()); // not a known error yet
        transition("priya.raman", id, Map.of("status", "INVESTIGATING")).andExpect(status().isOk());
        transition("priya.raman", id, Map.of("status", "ROOT_CAUSE_IDENTIFIED")).andExpect(status().isOk());
        transition("priya.raman", id, Map.of("status", "KNOWN_ERROR")).andExpect(status().isOk());
        mvc.perform(jsonBody(as("priya.raman", post("/api/problems/" + id + "/known-error-article")), Map.of("publish", true)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kbArticleStatus").value("PUBLISHED"));
        assertThat(getJson("kavya.reddy", "/api/knowledge?type=KNOWN_ERROR&size=100").get("content").findValuesAsText("title"))
                .anyMatch(t -> t.startsWith("Known error: Printer queue stalls"));

        mvc.perform(as("kavya.reddy", get("/api/problems"))).andExpect(status().isForbidden());
        mvc.perform(as("ananya.bose", get("/api/problems/" + id))).andExpect(status().isOk());
        mvc.perform(jsonBody(as("ananya.bose", post("/api/problems")), Map.of("title", "Auditor problem"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("deepa.nair", post("/api/problems")), Map.of("title", "Asset manager problem"))).andExpect(status().isForbidden());
        // Cancelling a problem that was never resolved needs a reason.
        JsonNode other = create("riya.varma", Map.of("title", unique("Duplicate problem")));
        transition("sunita.joshi", other.get("id").asLong(), Map.of("status", "CLOSED")).andExpect(status().isBadRequest());
        transition("sunita.joshi", other.get("id").asLong(), Map.of("status", "CLOSED", "note", "Duplicate of " + p.get("number").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.closureNote").value("Cancelled: Duplicate of " + p.get("number").asText()));
        JsonNode list = getJson("riya.varma", "/api/problems?status=CLOSED,KNOWN_ERROR&search=" + p.get("number").asText() + "&sort=createdAt,asc");
        assertThat(list.get("content").findValuesAsText("number")).containsExactly(p.get("number").asText());
        mvc.perform(as("riya.varma", get("/api/problems?status=BOGUS"))).andExpect(status().isBadRequest());
        mvc.perform(as("riya.varma", get("/api/problems?sort=password"))).andExpect(status().isBadRequest());
    }

    @Test
    void anIncidentBelongsToOneProblem() throws Exception {
        long inc = incident(unique("Shared drive mapping fails"), "EUC");
        JsonNode a = create("riya.varma", Map.of("title", unique("Shared drive problem A"), "incidentIds", List.of(inc)));
        JsonNode b = create("riya.varma", Map.of("title", unique("Shared drive problem B")));
        mvc.perform(jsonBody(as("riya.varma", post("/api/problems/" + b.get("id").asLong() + "/incidents")), Map.of("incidentIds", List.of(inc))))
                .andExpect(status().isConflict());
        mvc.perform(as("riya.varma", delete("/api/problems/" + a.get("id").asLong() + "/incidents/" + inc))).andExpect(status().isOk());
        mvc.perform(jsonBody(as("riya.varma", post("/api/problems/" + b.get("id").asLong() + "/incidents")), Map.of("incidentIds", List.of(inc))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.incidents[0].id").value(inc));
    }

    @Test
    void recurringIncidentsAreSuggestedAndBecomeAProblem() throws Exception {
        String token = "zq" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        List<Long> ids = new java.util.ArrayList<>();
        String[] titles = {"Lobby badge reader " + token + " rejects access cards", "Badge reader " + token + " in lobby rejects my access card",
                "Lobby badge reader " + token + " rejecting access cards again"};
        for (int i = 0; i < titles.length; i++) {
            long id = incident(titles[i], "FACILITIES");
            ids.add(id);
            // Older than the 72 h duplicate window, so each report stays a separate (recurring) incident.
            // (Settings changed by other tests may still link them as duplicates; recurring detection looks at top-level incidents.)
            jdbc.update("update incidents set created_at = now() - make_interval(days => ?), parent_id = null where id = ?", 10 - i * 3, id);
        }
        JsonNode cluster = null;
        for (JsonNode c : getJson("riya.varma", "/api/problems/recurring?days=30&minSize=3")) {
            if (c.get("incidents").findValuesAsText("id").containsAll(ids.stream().map(String::valueOf).toList())) {
                cluster = c;
            }
        }
        assertThat(cluster).as("cluster of the three badge reader incidents").isNotNull();
        assertThat(cluster.get("serviceName").asText()).isEqualTo("Workplace Facilities");
        assertThat(cluster.get("suggestedTitle").asText()).startsWith("Recurring: ");

        JsonNode problem = body(mvc.perform(jsonBody(as("riya.varma", post("/api/problems/from-cluster")), Map.of("incidentIds", ids)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(problem.get("incidents")).hasSize(3);
        for (JsonNode c : getJson("riya.varma", "/api/problems/recurring?days=30&minSize=3")) {
            assertThat(c.get("incidents").findValuesAsText("id")).doesNotContainAnyElementsOf(ids.stream().map(String::valueOf).toList());
        }

        // SA AI root-cause suggestion: without an LLM the deterministic engine answers, labelled as such.
        jdbc.update("update incidents set root_cause = 'Card reader firmware 2.1 rejects new card batch' where id in (?, ?)", ids.get(0), ids.get(1));
        JsonNode ai = body(mvc.perform(as("riya.varma", post("/api/ai/problems/" + problem.get("id").asLong() + "/root-cause")))
                .andExpect(status().isOk()).andReturn());
        assertThat(ai.get("source").asText()).isEqualTo("FALLBACK");
        assertThat(ai.get("notice").asText()).contains("No LLM was used");
        assertThat(ai.get("fallbackReason").asText()).isNotBlank();
        assertThat(ai.at("/data/hypotheses/0/cause").asText()).isEqualTo("Card reader firmware 2.1 rejects new card batch");
        assertThat(ai.at("/data/hypotheses/0/likelihood").asText()).isEqualTo("HIGH");
        assertThat(ai.at("/data/incidentsAnalysed").asInt()).isEqualTo(3);

        JsonNode analytics = getJson("priya.raman", "/api/problems/analytics?days=90");
        assertThat(analytics.get("total").asLong()).isPositive();
        assertThat(analytics.get("byStatus").has("KNOWN_ERROR")).isTrue();
        assertThat(analytics.get("knownErrors").asLong()).isPositive(); // demo payroll known error
        mvc.perform(as("kavya.reddy", post("/api/ai/problems/" + problem.get("id").asLong() + "/root-cause"))).andExpect(status().isForbidden());
    }

    @Test
    void demoProblemsAreLinkedToThePayrollIncidents() throws Exception {
        JsonNode list = getJson("priya.raman", "/api/problems?knownError=true&serviceId=" + service("PAYROLL") + "&size=100");
        JsonNode payroll = list.get("content").get(0);
        assertThat(payroll.get("demo").asBoolean()).isTrue();
        assertThat(payroll.get("incidentCount").asLong()).isPositive();
        JsonNode detail = getJson("priya.raman", "/api/problems/" + payroll.get("id").asLong());
        assertThat(detail.get("kbArticleNumber").isNull()).isFalse();
        assertThat(detail.get("changes").findValuesAsText("number")).isNotEmpty(); // the permanent fix change
        assertThat(detail.get("actions")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(getJson("riya.varma", "/api/my-work?limit=50").at("/problems/items").findValuesAsText("number")).contains(payroll.get("number").asText());
    }
}

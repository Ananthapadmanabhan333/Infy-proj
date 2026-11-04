package com.nexus360.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Knowledge Release 2: review workflow, versions and restore, one rating per user, rating sort, deletion and stats. */
class KnowledgeRelease2IT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private Map<String, Object> article(String title, String content, String status) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("summary", "How to recover the quarterly ledger export");
        body.put("content", content);
        body.put("articleType", "SOLUTION");
        body.put("category", "APPLICATION");
        body.put("tags", List.of("ledger", "export"));
        body.put("status", status);
        return body;
    }

    private JsonNode ok(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return body(actions.andExpect(status().isOk()).andReturn());
    }

    @Test
    void reviewWorkflowVersionsAndRestore() throws Exception {
        String title = unique("Ledger export recovery");
        mvc.perform(jsonBody(as("riya.varma", post("/api/knowledge")), article(title, "Step one: re-run the ledger export job.", "PUBLISHED")))
                .andExpect(status().isForbidden()); // agents cannot publish directly
        mvc.perform(jsonBody(as("kavya.reddy", post("/api/knowledge")), article(title, "Step one: re-run the ledger export job.", "DRAFT")))
                .andExpect(status().isForbidden()); // employees cannot author
        JsonNode draft = body(mvc.perform(jsonBody(as("riya.varma", post("/api/knowledge")),
                article(title, "Step one: re-run the ledger export job.", "DRAFT"))).andExpect(status().isCreated()).andReturn());
        long id = draft.get("id").asLong();
        assertThat(draft.get("currentVersion").asInt()).isEqualTo(1);
        assertThat(draft.get("averageRating").isNull()).isTrue();
        String base = "/api/knowledge/" + id;

        JsonNode submitted = ok(mvc.perform(as("riya.varma", post(base + "/submit"))));
        assertThat(submitted.get("status").asText()).isEqualTo("IN_REVIEW");
        mvc.perform(as("riya.varma", post(base + "/submit"))).andExpect(status().isConflict());
        mvc.perform(as("riya.varma", post(base + "/publish"))).andExpect(status().isForbidden());
        mvc.perform(as("kavya.reddy", get(base))).andExpect(status().isNotFound()); // employees only see published articles
        assertThat(getJson("priya.raman", "/api/knowledge/stats").get("inReview").asLong()).isPositive();

        JsonNode published = ok(mvc.perform(as("priya.raman", post(base + "/publish"))));
        assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.get("publishedAt").isNull()).isFalse();
        mvc.perform(jsonBody(as("riya.varma", put(base)), article(title, "Agent edits after publication are not allowed.", "PUBLISHED")))
                .andExpect(status().isForbidden());

        Map<String, Object> edit = article(title + " v2", "Step one: clear the cache. Step two: re-run the export job.", "PUBLISHED");
        edit.put("changeNote", "Added the cache step");
        JsonNode v2 = ok(mvc.perform(jsonBody(as("priya.raman", put(base)), edit)));
        assertThat(v2.get("currentVersion").asInt()).isEqualTo(2);
        // Saving without content changes does not create a version.
        assertThat(ok(mvc.perform(jsonBody(as("priya.raman", put(base)), edit))).get("currentVersion").asInt()).isEqualTo(2);

        JsonNode versions = getJson("riya.varma", base + "/versions");
        assertThat(versions.findValuesAsText("version")).containsExactly("2", "1");
        assertThat(versions.get(0).get("changeNote").asText()).isEqualTo("Added the cache step");
        assertThat(versions.get(0).get("editorName").asText()).isEqualTo("Priya Raman");
        assertThat(versions.get(1).get("editorName").asText()).isEqualTo("Riya Varma");

        mvc.perform(as("riya.varma", post(base + "/versions/1/restore"))).andExpect(status().isForbidden());
        mvc.perform(as("priya.raman", post(base + "/versions/9/restore"))).andExpect(status().isNotFound());
        JsonNode restored = ok(mvc.perform(as("priya.raman", post(base + "/versions/1/restore"))));
        assertThat(restored.get("currentVersion").asInt()).isEqualTo(3);
        assertThat(restored.get("title").asText()).isEqualTo(title);
        assertThat(restored.get("content").asText()).isEqualTo("Step one: re-run the ledger export job.");
        assertThat(getJson("priya.raman", base + "/versions").get(0).get("changeNote").asText()).isEqualTo("Restored version 1");

        JsonNode archived = ok(mvc.perform(as("kiran.rao", post(base + "/archive"))));
        assertThat(archived.get("status").asText()).isEqualTo("ARCHIVED");
        mvc.perform(as("kiran.rao", post(base + "/archive"))).andExpect(status().isConflict());
    }

    @Test
    void ratingsAreOnePerUserAndDriveTheCounters() throws Exception {
        JsonNode created = body(mvc.perform(jsonBody(as("priya.raman", post("/api/knowledge")),
                article(unique("Ledger rating article"), "Rate this article to test the aggregates.", "PUBLISHED")))
                .andExpect(status().isCreated()).andReturn());
        long id = created.get("id").asLong();
        String feedback = "/api/knowledge/" + id + "/feedback";

        JsonNode first = ok(mvc.perform(jsonBody(as("kavya.reddy", post(feedback)), Map.of("rating", 4))));
        assertThat(first.get("ratingCount").asInt()).isEqualTo(1);
        assertThat(first.get("myRating").asInt()).isEqualTo(4);
        JsonNode rerated = ok(mvc.perform(jsonBody(as("kavya.reddy", post(feedback)), Map.of("rating", 2, "comment", "Missing a step"))));
        assertThat(rerated.get("ratingCount").asInt()).isEqualTo(1); // re-rating replaces the user's rating
        assertThat(rerated.get("averageRating").asDouble()).isEqualTo(2.0);
        assertThat(rerated.get("helpfulCount").asInt()).isZero();
        assertThat(rerated.get("notHelpfulCount").asInt()).isEqualTo(1);

        ok(mvc.perform(jsonBody(as("amit.shah", post(feedback)), Map.of("rating", 5))));
        JsonNode legacy = ok(mvc.perform(jsonBody(as("sneha.patil", post(feedback)), Map.of("helpful", true)))); // Release 1 form
        assertThat(legacy.get("ratingCount").asInt()).isEqualTo(3);
        assertThat(legacy.get("averageRating").asDouble()).isEqualTo(4.0);
        assertThat(legacy.get("helpfulCount").asInt()).isEqualTo(2);
        assertThat(legacy.get("myRating").asInt()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from kb_article_ratings where article_id = ?", Long.class, id)).isEqualTo(3);
        assertThat(getJson("kavya.reddy", "/api/knowledge/" + id).get("myRating").asInt()).isEqualTo(2);

        mvc.perform(jsonBody(as("amit.shah", post(feedback)), Map.of("rating", 6))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("amit.shah", post(feedback)), Map.of())).andExpect(status().isBadRequest());

        JsonNode byRating = getJson("kavya.reddy", "/api/knowledge?sort=rating&size=100").get("content");
        double previous = Double.MAX_VALUE;
        for (JsonNode a : byRating) {
            double avg = a.get("averageRating").isNull() ? 0 : a.get("averageRating").asDouble();
            assertThat(avg).isLessThanOrEqualTo(previous);
            previous = avg;
        }
        assertThat(byRating.findValuesAsText("status")).containsOnly("PUBLISHED");
    }

    @Test
    void onlyAdministratorsDeleteAndCitingIncidentsAreUnlinked() throws Exception {
        JsonNode created = body(mvc.perform(jsonBody(as("priya.raman", post("/api/knowledge")),
                article(unique("Ledger article to delete"), "This article will be cited and then deleted.", "PUBLISHED")))
                .andExpect(status().isCreated()).andReturn());
        long articleId = created.get("id").asLong();
        String number = created.get("number").asText();
        long incident = createIncident("amit.shah", Map.of("title", unique("Quarterly ledger export missing rows"),
                "description", "The export stops halfway")).at("/incident/id").asLong();
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + incident + "/transition")), Map.of("status", "RESOLVED",
                "resolutionCode", "FIXED", "resolutionNotes", "Followed the article.", "resolutionArticleId", articleId)))
                .andExpect(status().isOk());
        assertThat(getJson("priya.raman", "/api/knowledge/" + articleId).get("linkedIncidentCount").asLong()).isEqualTo(1);

        mvc.perform(as("priya.raman", delete("/api/knowledge/" + articleId))).andExpect(status().isForbidden());
        mvc.perform(as("riya.varma", delete("/api/knowledge/" + articleId))).andExpect(status().isForbidden());
        mvc.perform(as("rajesh.iyer", delete("/api/knowledge/" + articleId))).andExpect(status().isNoContent());
        mvc.perform(as("priya.raman", get("/api/knowledge/" + articleId))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select resolution_article_id from incidents where id = ?", Long.class, incident)).isNull();
        assertThat(jdbc.queryForObject("select resolution_notes from incidents where id = ?", String.class, incident))
                .isEqualTo("Followed the article.");
        assertThat(jdbc.queryForObject("select count(*) from kb_article_versions where article_id = ?", Long.class, articleId)).isZero();
        Map<String, Object> audit = jdbc.queryForMap("select actor_email, details::text as details from audit_logs "
                + "where action = 'KB_ARTICLE_DELETED' and entity_id = ?", number);
        assertThat(audit.get("actor_email")).isEqualTo(email("rajesh.iyer"));
        assertThat(audit.get("details").toString()).contains("\"incidentsUnlinked\": 1");
    }

    @Test
    void statsCountPublishedArticlesByTypeAndActiveRunbooks() throws Exception {
        JsonNode stats = getJson("kavya.reddy", "/api/knowledge/stats");
        JsonNode byType = stats.get("countsByType");
        assertThat(byType.fieldNames()).toIterable().containsExactlyInAnyOrder("GUIDE", "SOLUTION", "KNOWN_ERROR", "FAQ");
        long sum = 0;
        for (JsonNode count : byType) {
            sum += count.asLong();
        }
        assertThat(sum).isEqualTo(stats.get("published").asLong());
        assertThat(stats.get("runbookCount").asLong())
                .isEqualTo(jdbc.queryForObject("select count(*) from runbooks where active", Long.class)).isPositive();
        assertThat(stats.has("inReview")).isTrue();
    }
}

package com.nexus360.major;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** Major incident command lifecycle, status-page publication, and the post-incident review (auto timeline, approval, PDF). */
class MajorIncidentIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long user(String u) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email(u));
    }

    private long incident(String title) throws Exception {
        return createIncident("rohan.kulkarni", Map.of("title", unique(title), "description", unique("Quasar ledger sync halted for finance"),
                "impact", "HIGH", "urgency", "HIGH")).at("/incident/id").asLong();
    }

    private void resolve(long incident) throws Exception {
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + incident + "/transition")), Map.of("status", "RESOLVED", "resolutionCode", "FIXED",
                "resolutionNotes", "Restarted the ledger sync workers.", "rootCause", "Sync workers exhausted their file handles"))).andExpect(status().isOk());
    }

    @Test
    void declaringAMajorIncidentOpensTheCommandRecordThroughEitherEndpoint() throws Exception {
        // The existing incident flag endpoint creates the command record (declaring manager takes command).
        long first = incident("Quasar ledger halt");
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + first + "/major")), Map.of("major", true))).andExpect(status().isOk());
        JsonNode viaFlag = getJson("riya.varma", "/api/major-incidents/by-incident/" + first);
        assertThat(viaFlag.get("number").asText()).startsWith("MI-");
        assertThat(viaFlag.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(viaFlag.at("/commander/name").asText()).isEqualTo("Sunita Joshi");
        assertThat(viaFlag.at("/incident/major").asBoolean()).isTrue();
        assertThat(viaFlag.get("timeline").findValuesAsText("entryType")).contains("SYSTEM");

        // The command endpoint declares with roles; declaring twice conflicts; agents cannot declare.
        long second = incident("Quasar invoice relay down");
        mvc.perform(jsonBody(as("riya.varma", post("/api/major-incidents")), Map.of("incidentId", second))).andExpect(status().isForbidden());
        JsonNode declared = body(mvc.perform(jsonBody(as("priya.raman", post("/api/major-incidents")), Map.of("incidentId", second,
                "commanderId", user("sunita.joshi"), "techLeadId", user("dev.nair"), "commsLeadId", user("riya.varma"), "summary", "Invoice relay outage")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(declared.at("/commander/name").asText()).isEqualTo("Sunita Joshi");
        assertThat(declared.at("/techLead/name").asText()).isEqualTo("Dev Nair");
        assertThat(declared.at("/commsLead/name").asText()).isEqualTo("Riya Varma");
        assertThat(declared.get("timeline").findValuesAsText("entryType")).contains("ROLE");
        assertThat(jdbc.queryForObject("select is_major from incidents where id = ?", Boolean.class, second)).isTrue();
        mvc.perform(jsonBody(as("priya.raman", post("/api/major-incidents")), Map.of("incidentId", second))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'MAJOR_INCIDENT_ROLE'", Long.class,
                user("dev.nair"))).isPositive();
        mvc.perform(as("kavya.reddy", get("/api/major-incidents"))).andExpect(status().isForbidden());
        assertThat(getJson("ananya.bose", "/api/major-incidents?status=ACTIVE").get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void commandLifecyclePublishesToTheStatusPageAndClosingOpensAReview() throws Exception {
        long incident = incident("Quasar treasury feed frozen");
        // An alert fired before the incident was recorded and is linked to it (feeds MTTD and the review timeline).
        JsonNode alert = body(mvc.perform(jsonBody(as("riya.varma", post("/api/alerts")), Map.of("title", unique("Treasury feed lag"), "severity", "CRITICAL")))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + alert.at("/alert/id").asLong() + "/link-incident")), Map.of("incidentId", incident)))
                .andExpect(status().isOk());
        JsonNode mi = body(mvc.perform(jsonBody(as("sunita.joshi", post("/api/major-incidents")), Map.of("incidentId", incident, "techLeadId",
                user("dev.nair"), "commsLeadId", user("riya.varma")))).andExpect(status().isCreated()).andReturn());
        long id = mi.get("id").asLong();
        assertThat(mi.get("alerts")).hasSize(1);

        // Responders write the war-room timeline; system entry types are reserved.
        mvc.perform(jsonBody(as("dev.nair", post("/api/major-incidents/" + id + "/timeline")), Map.of("entryType", "DECISION", "body",
                "Fail over the treasury feed to the DR site"))).andExpect(status().isCreated());
        mvc.perform(jsonBody(as("dev.nair", post("/api/major-incidents/" + id + "/timeline")), Map.of("entryType", "SYSTEM", "body", "x")))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("dev.nair", put("/api/major-incidents/" + id)), Map.of("impactSummary", "Treasury cannot see bank balances",
                "investigationNotes", "Feed consumer stuck on a poison message"))).andExpect(status().isOk());

        // Internal update by a responder; publishing needs a status page editor, the commander or the comms lead.
        JsonNode internal = body(mvc.perform(jsonBody(as("dev.nair", post("/api/major-incidents/" + id + "/updates")), Map.of("status", "INVESTIGATING",
                "message", "Investigating the treasury feed."))).andExpect(status().isCreated()).andReturn());
        assertThat(internal.get("published").asBoolean()).isFalse();
        mvc.perform(jsonBody(as("dev.nair", post("/api/major-incidents/" + id + "/updates")), Map.of("status", "IDENTIFIED", "message", "x",
                "publishToStatusPage", true))).andExpect(status().isForbidden());
        String publicTitle = unique("Treasury feed delayed");
        JsonNode published = body(mvc.perform(jsonBody(as("riya.varma", post("/api/major-incidents/" + id + "/updates")), Map.of("status", "IDENTIFIED",
                "title", publicTitle, "message", "Bank balances are delayed; a fix is in progress.", "publishToStatusPage", true)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(published.get("published").asBoolean()).isTrue();
        long statusUpdate = published.get("statusUpdateId").asLong();
        assertThat(jdbc.queryForObject("select is_public from status_updates where id = ?", Boolean.class, statusUpdate)).isTrue();
        assertThat(jdbc.queryForObject("select incident_id from status_updates where id = ?", Long.class, statusUpdate)).isEqualTo(incident);
        assertThat(getJson("kavya.reddy", "/api/status-page").get("updates").findValuesAsText("title")).contains(publicTitle);
        // The earlier internal update can be published later by the comms lead, once.
        mvc.perform(as("riya.varma", post("/api/major-incidents/" + id + "/updates/" + internal.get("id").asLong() + "/publish"))).andExpect(status().isOk());
        mvc.perform(as("riya.varma", post("/api/major-incidents/" + id + "/updates/" + internal.get("id").asLong() + "/publish")))
                .andExpect(status().isConflict());

        // Action items appear in the owner's My Work.
        JsonNode action = body(mvc.perform(jsonBody(as("dev.nair", post("/api/major-incidents/" + id + "/actions")), Map.of("title", "Add a poison-message "
                + "alarm", "ownerId", user("meera.sharma"), "dueDate", LocalDate.now().plusDays(3).toString()))).andExpect(status().isCreated()).andReturn());
        assertThat(action.get("source").asText()).isEqualTo("MAJOR_INCIDENT");
        assertThat(getJson("meera.sharma", "/api/my-work?limit=50").at("/actionItems/items").findValuesAsText("id"))
                .contains(action.get("id").asText());

        // Lifecycle: only commanders move the status; RESOLVED needs a resolution; CLOSED needs the incident resolved.
        mvc.perform(jsonBody(as("dev.nair", post("/api/major-incidents/" + id + "/status")), Map.of("status", "MONITORING"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/major-incidents/" + id + "/status")), Map.of("status", "MONITORING"))).andExpect(status().isOk());
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/major-incidents/" + id + "/status")), Map.of("status", "CLOSED"))).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/major-incidents/" + id + "/status")), Map.of("status", "RESOLVED")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("note"));
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/major-incidents/" + id + "/status")), Map.of("status", "RESOLVED", "note",
                "Feed failed over to DR; balances current."))).andExpect(status().isOk());
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/major-incidents/" + id + "/status")), Map.of("status", "CLOSED"))).andExpect(status().isConflict());
        resolve(incident);
        JsonNode closed = body(mvc.perform(jsonBody(as("sunita.joshi", post("/api/major-incidents/" + id + "/status")), Map.of("status", "CLOSED")))
                .andExpect(status().isOk()).andReturn());
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.at("/pir/status").asText()).isEqualTo("DRAFT");
        assertThat(closed.get("resolutionSummary").asText()).contains("DR");
        assertThat(closed.get("allowedTransitions")).isEmpty();

        // The review: auto-built timeline and key times from the incident, alert and war-room records.
        long pir = closed.at("/pir/id").asLong();
        JsonNode review = getJson("riya.varma", "/api/reviews/" + pir);
        assertThat(review.get("number").asText()).startsWith("PIR-");
        assertThat(review.at("/owner/name").asText()).isEqualTo("Sunita Joshi"); // the commander owns it
        List<String> kinds = review.get("timeline").findValuesAsText("kind");
        assertThat(kinds).contains("ALERT", "WAR_ROOM", "UPDATE", "HISTORY");
        assertThat(review.get("timeline").findValuesAsText("detail")).contains("Fail over the treasury feed to the DR site");
        assertThat(review.at("/metrics/alerts").asInt()).isEqualTo(1);
        assertThat(review.at("/metrics/minutesToResolve").isNumber()).isTrue();
        assertThat(review.at("/metrics/declaredAt").asText()).isNotBlank();
        assertThat(review.get("rootCause").asText()).isEqualTo("Sync workers exhausted their file handles"); // pre-filled from the incident
        assertThat(review.get("impact").asText()).isEqualTo("Treasury cannot see bank balances"); // from the command record
        List<String> times = review.get("timeline").findValuesAsText("at");
        assertThat(times).isSorted();

        // Submitting needs the analysis and a corrective or preventive action.
        MvcResult missing = mvc.perform(as("sunita.joshi", post("/api/reviews/" + pir + "/submit"))).andExpect(status().isBadRequest()).andReturn();
        assertThat(body(missing).get("fieldErrors").findValuesAsText("field")).containsExactly("actions"); // summary and impact were pre-filled
        mvc.perform(jsonBody(as("dev.nair", put("/api/reviews/" + pir)), Map.of("summary", "A poison message stopped the treasury feed consumer.",
                "contributingFactors", List.of("No dead-letter queue", "No consumer-lag alarm"), "detectionGaps", "Detected by users",
                "lessonsLearned", "Alert on consumer lag"))).andExpect(status().isOk()); // the tech lead may edit
        mvc.perform(jsonBody(as("rahul.das", put("/api/reviews/" + pir)), Map.of("summary", "x"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("dev.nair", post("/api/reviews/" + pir + "/actions")), Map.of("title", "Add a dead-letter queue to the feed consumer",
                "actionType", "CORRECTIVE", "ownerId", user("dev.nair"), "dueDate", LocalDate.now().plusDays(14).toString()))).andExpect(status().isCreated());
        JsonNode submitted = body(mvc.perform(as("sunita.joshi", post("/api/reviews/" + pir + "/submit"))).andExpect(status().isOk()).andReturn());
        assertThat(submitted.get("status").asText()).isEqualTo("IN_REVIEW");
        long approval = submitted.get("approvalId").asLong();
        assertThat(jdbc.queryForObject("select approval_type from approvals where id = ?", String.class, approval)).isEqualTo("PIR");
        mvc.perform(jsonBody(as("dev.nair", put("/api/reviews/" + pir)), Map.of("summary", "late edit"))).andExpect(status().isConflict());

        // Approval by the PIR approvers only: the requester (segregation of duties) and agents cannot decide; a rejection needs a reason.
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/approvals/" + approval + "/decision")), Map.of("approved", true))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("riya.varma", post("/api/approvals/" + approval + "/decision")), Map.of("approved", true))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("priya.raman", post("/api/approvals/" + approval + "/decision")), Map.of("approved", false, "comment",
                "Quantify the impact"))).andExpect(status().isOk());
        JsonNode rejected = getJson("sunita.joshi", "/api/reviews/" + pir);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("reviewComment").asText()).isEqualTo("Quantify the impact");
        mvc.perform(jsonBody(as("sunita.joshi", put("/api/reviews/" + pir)), Map.of("impact", "Treasury blind for 95 minutes; 3 payments delayed")))
                .andExpect(status().isOk());
        long second = body(mvc.perform(as("sunita.joshi", post("/api/reviews/" + pir + "/submit"))).andExpect(status().isOk()).andReturn())
                .get("approvalId").asLong();
        mvc.perform(jsonBody(as("priya.raman", post("/api/approvals/" + second + "/decision")), Map.of("approved", true, "comment", "Good review")))
                .andExpect(status().isOk());
        JsonNode approved = getJson("ananya.bose", "/api/reviews/" + pir);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("approvedByName").asText()).isEqualTo("Priya Raman");
        assertThat(approved.get("canEdit").asBoolean()).isFalse();

        // PDF export (readers), audited.
        MvcResult pdf = mvc.perform(as("ananya.bose", get("/api/reviews/" + pir + "/pdf"))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")).andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("PIR-"))).andReturn();
        byte[] bytes = pdf.getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(bytes.length).isGreaterThan(1500);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'PIR_EXPORTED' and entity_id = ?", Long.class,
                String.valueOf(pir))).isEqualTo(1);
        mvc.perform(as("kavya.reddy", get("/api/reviews/" + pir + "/pdf"))).andExpect(status().isForbidden());
        assertThat(getJson("riya.varma", "/api/reviews?status=APPROVED").get("content").findValuesAsText("number")).contains(approved.get("number").asText());
    }

    @Test
    void demoDataHasThePayrollCommandAndBothReviews() throws Exception {
        JsonNode list = getJson("sunita.joshi", "/api/major-incidents?size=100");
        JsonNode payroll = null;
        for (JsonNode m : list.get("content")) {
            if (m.get("demo").asBoolean() && m.at("/commsLead/name").asText().equals("Priya Raman") && m.at("/techLead/name").asText().equals("Riya Varma")) {
                payroll = m;
            }
        }
        assertThat(payroll).isNotNull();
        JsonNode detail = getJson("sunita.joshi", "/api/major-incidents/" + payroll.get("id").asLong());
        assertThat(detail.get("updates")).hasSize(2);
        assertThat(detail.get("updates").findValuesAsText("published")).contains("true", "false");
        assertThat(detail.get("actionItems")).hasSize(3);
        assertThat(detail.get("timeline").findValuesAsText("entryType")).contains("DECISION", "INVESTIGATION", "ROLE");
        assertThat(detail.at("/pir/status").asText()).isEqualTo("DRAFT");
        JsonNode approved = getJson("sunita.joshi", "/api/reviews?status=APPROVED&search=portal");
        assertThat(approved.get("content").findValuesAsText("demo")).contains("true");
    }
}

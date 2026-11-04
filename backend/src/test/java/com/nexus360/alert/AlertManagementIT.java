package com.nexus360.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Alert inbox and operations: manual alerts, filters, counts, acknowledge / resolve / suppress / unsuppress / assign / bulk,
 * conversion to incidents (intake pipeline) and linking, suppression and routing rules, escalation of unacknowledged alerts
 * (exactly once per level), the legacy inbound integration endpoint, the ALERT_RECEIVED workflow trigger, My Work, permissions
 * and the demo payroll alert group.
 */
class AlertManagementIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AlertEscalationService escalation;

    private long teamId(String code) {
        return jdbc.queryForObject("select id from teams where code = ?", Long.class, code);
    }

    private JsonNode manual(String user, Map<String, Object> body) throws Exception {
        return body(mvc.perform(jsonBody(as(user, post("/api/alerts")), body)).andExpect(status().isCreated()).andReturn());
    }

    private Map<String, Object> alertBody(String title, String severity, Map<String, String> labels) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("severity", severity);
        m.put("labels", labels);
        m.put("description", "Raised by an integration test");
        return m;
    }

    /** Notifications of a type for a user, optionally only those whose title mentions {@code text}. */
    private long notifications(String user, String type, String text) {
        Long n = jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id where lower(u.email) = lower(?) "
                + "and n.type = ? and (cast(? as text) is null or n.title like ?)", Long.class, email(user), type, text, text == null ? null : "%" + text + "%");
        return n == null ? 0 : n;
    }

    @Test
    void operatorActionsAreValidatedRecordedAndPermissioned() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = manual("riya.varma", alertBody("Login page slow " + run, "MAJOR", Map.of("run", run)));
        long id = created.at("/alert/id").asLong();
        assertThat(created.at("/alert/number").asText()).startsWith("ALR-");
        assertThat(created.at("/alert/status").asText()).isEqualTo("OPEN");
        assertThat(created.at("/alert/sourceKind").asText()).isEqualTo("MANUAL");
        // The same title again is deduplicated (fingerprint from title, service and CI).
        JsonNode repeat = manual("riya.varma", alertBody("Login page slow " + run, "MAJOR", Map.of("run", run)));
        assertThat(repeat.at("/alert/id").asLong()).isEqualTo(id);
        assertThat(repeat.at("/alert/occurrenceCount").asInt()).isEqualTo(2);

        // Validation and permissions.
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts")), Map.of("title", "x"))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts")), Map.of("title", "Valid title", "severity", "SEVERE"))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts")), Map.of("title", "Valid title", "serviceId", 999_999)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("serviceId"));
        mvc.perform(as("kavya.reddy", get("/api/alerts"))).andExpect(status().isForbidden());
        mvc.perform(as("kavya.reddy", get("/api/alerts/" + id))).andExpect(status().isForbidden());
        JsonNode auditorView = getJson("ananya.bose", "/api/alerts/" + id);
        assertThat(auditorView.get("allowedActions")).isEmpty();
        mvc.perform(as("ananya.bose", post("/api/alerts/" + id + "/acknowledge"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("ananya.bose", post("/api/alerts")), alertBody("Auditor alert " + run, "INFO", Map.of()))).andExpect(status().isForbidden());

        // Acknowledge once.
        JsonNode ack = body(mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + id + "/acknowledge")), Map.of("note", "Looking")))
                .andExpect(status().isOk()).andReturn());
        assertThat(ack.at("/alert/status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(ack.at("/alert/acknowledgedByName").asText()).isEqualTo("Riya Varma");
        assertThat(ack.at("/alert/nextEscalationAt").isNull()).isTrue();
        mvc.perform(as("riya.varma", post("/api/alerts/" + id + "/acknowledge"))).andExpect(status().isConflict());

        // Suppress for 5 minutes, unsuppress; suppress again and let the period end.
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + id + "/suppress")), Map.of("minutes", 2, "reason", "noise")))
                .andExpect(status().isBadRequest());
        JsonNode sup = body(mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + id + "/suppress")), Map.of("minutes", 5, "reason", "Known noise")))
                .andExpect(status().isOk()).andReturn());
        assertThat(sup.at("/alert/status").asText()).isEqualTo("SUPPRESSED");
        assertThat(sup.get("suppressedUntil").isNull()).isFalse();
        assertThat(sup.get("allowedActions").toString()).contains("UNSUPPRESS");
        JsonNode unsup = body(mvc.perform(as("riya.varma", post("/api/alerts/" + id + "/unsuppress"))).andExpect(status().isOk()).andReturn());
        assertThat(unsup.at("/alert/status").asText()).isEqualTo("OPEN");
        mvc.perform(as("riya.varma", post("/api/alerts/" + id + "/unsuppress"))).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + id + "/suppress")), Map.of("minutes", 5, "reason", "Known noise")))
                .andExpect(status().isOk());
        testClock().advance(Duration.ofMinutes(6));
        escalation.runEscalations();
        JsonNode back = getJson("riya.varma", "/api/alerts/" + id);
        assertThat(back.at("/alert/status").asText()).isEqualTo("OPEN");
        assertThat(back.get("history").findValuesAsText("type")).contains("SUPPRESSED", "UNSUPPRESSED", "ACKNOWLEDGED", "REPEATED");
        testClock().reset();

        // Assign to a team.
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + id + "/assign")), Map.of("teamId", 999_999))).andExpect(status().isBadRequest());
        JsonNode assigned = body(mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + id + "/assign")), Map.of("teamId", teamId("NET-OPS"),
                "note", "Network latency"))).andExpect(status().isOk()).andReturn());
        assertThat(assigned.at("/alert/teamName").asText()).isEqualTo("Network Operations");

        // Convert to an incident through the intake pipeline; a second conversion is refused.
        JsonNode converted = body(mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + id + "/incident")), Map.of()))
                .andExpect(status().isCreated()).andReturn());
        String incidentNumber = converted.get("incidentNumber").asText();
        assertThat(incidentNumber).startsWith("INC-");
        assertThat(converted.at("/alert/alert/incidentNumber").asText()).isEqualTo(incidentNumber);
        assertThat(converted.at("/alert/alert/status").asText()).isEqualTo("ACKNOWLEDGED"); // converting acknowledges
        long incidentId = converted.get("incidentId").asLong();
        JsonNode incident = getJson("riya.varma", "/api/incidents/" + incidentId);
        assertThat(incident.get("title").asText()).isEqualTo("[MAJOR] Login page slow " + run);
        assertThat(incident.get("description").asText()).contains(created.at("/alert/number").asText());
        assertThat(incident.get("impact").asText()).isEqualTo("HIGH");
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + id + "/incident")), Map.of())).andExpect(status().isConflict());
        assertThat(getJson("riya.varma", "/api/alerts?incidentId=" + incidentId).get("totalElements").asInt()).isEqualTo(1);

        // Link another alert to the same incident.
        JsonNode other = manual("dev.nair", alertBody("Login API errors " + run, "MINOR", Map.of("run", run)));
        long otherId = other.at("/alert/id").asLong();
        mvc.perform(jsonBody(as("dev.nair", post("/api/alerts/" + otherId + "/link-incident")), Map.of("incidentId", 999_999_999)))
                .andExpect(status().isBadRequest());
        JsonNode linked = body(mvc.perform(jsonBody(as("dev.nair", post("/api/alerts/" + otherId + "/link-incident")), Map.of("incidentId", incidentId)))
                .andExpect(status().isOk()).andReturn());
        assertThat(linked.get("linkedAlerts").asInt()).isEqualTo(2);
        assertThat(getJson("riya.varma", "/api/incidents/" + incidentId + "/comments").toString()).contains("was linked to this incident");

        // Inbox filters, counts, sort.
        JsonNode filtered = getJson("riya.varma", "/api/alerts?search=" + run + "&severity=MAJOR,MINOR&sort=severity,desc");
        assertThat(filtered.get("totalElements").asInt()).isEqualTo(2);
        assertThat(filtered.at("/content/0/severity").asText()).isEqualTo("MAJOR");
        assertThat(getJson("riya.varma", "/api/alerts?search=" + run + "&status=RESOLVED").get("totalElements").asInt()).isZero();
        assertThat(getJson("riya.varma", "/api/alerts?search=" + run + "&hasIncident=true&source=MANUAL").get("totalElements").asInt()).isEqualTo(2);
        assertThat(getJson("arjun.kapoor", "/api/alerts?search=" + run + "&scope=mine").get("totalElements").asInt()).isEqualTo(1); // NET-OPS member
        mvc.perform(as("riya.varma", get("/api/alerts?sort=bogus"))).andExpect(status().isBadRequest());
        mvc.perform(as("riya.varma", get("/api/alerts?status=WRONG"))).andExpect(status().isBadRequest());
        mvc.perform(as("riya.varma", get("/api/alerts?scope=everyone"))).andExpect(status().isBadRequest());
        JsonNode counts = getJson("riya.varma", "/api/alerts/counts");
        assertThat(counts.get("active").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(counts.get("bySeverity").has("CRITICAL")).isTrue();

        // Bulk resolve, then resolving again is refused.
        JsonNode bulk = body(mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/bulk")), Map.of("ids", List.of(id, otherId, 999_999), "action",
                "RESOLVE", "note", "Fixed by restarting the login service"))).andExpect(status().isOk()).andReturn());
        assertThat(bulk.get("processed").asInt()).isEqualTo(2);
        assertThat(bulk.get("skipped").asInt()).isEqualTo(1);
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/bulk")), Map.of("ids", List.of(id), "action", "DELETE"))).andExpect(status().isBadRequest());
        mvc.perform(as("riya.varma", post("/api/alerts/" + id + "/resolve"))).andExpect(status().isConflict());
        JsonNode resolved = getJson("riya.varma", "/api/alerts/" + id);
        assertThat(resolved.at("/alert/status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolvedByName").asText()).isEqualTo("Riya Varma");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action in ('ALERT_ACKNOWLEDGED', 'ALERT_SUPPRESSED', 'ALERT_CONVERTED', "
                + "'ALERT_LINKED', 'ALERT_ASSIGNED') and entity_id in (?, ?)", Long.class, created.at("/alert/number").asText(),
                other.at("/alert/number").asText())).isGreaterThanOrEqualTo(5);
    }

    @Test
    void bulkSuppressFollowsTheSingleAlertRules() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long open = manual("riya.varma", alertBody("Bulk suppress open " + run, "MAJOR", Map.of("run", run))).at("/alert/id").asLong();
        long acked = manual("riya.varma", alertBody("Bulk suppress acked " + run, "MINOR", Map.of("run", run))).at("/alert/id").asLong();
        long resolved = manual("riya.varma", alertBody("Bulk suppress resolved " + run, "MINOR", Map.of("run", run))).at("/alert/id").asLong();
        long suppressed = manual("riya.varma", alertBody("Bulk suppress already " + run, "MINOR", Map.of("run", run))).at("/alert/id").asLong();
        long untilResolved = manual("riya.varma", alertBody("Bulk suppress open-ended " + run, "MINOR", Map.of("run", run))).at("/alert/id").asLong();
        mvc.perform(as("riya.varma", post("/api/alerts/" + acked + "/acknowledge"))).andExpect(status().isOk());
        mvc.perform(as("riya.varma", post("/api/alerts/" + resolved + "/resolve"))).andExpect(status().isOk());
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + suppressed + "/suppress")), Map.of("reason", "Earlier suppression")))
                .andExpect(status().isOk());
        List<Long> ids = List.of(open, acked, resolved, suppressed, 999_999L);

        // Same role gate as the other bulk actions: employees and auditors are refused.
        Map<String, Object> request = Map.of("ids", ids, "action", "SUPPRESS", "minutes", 30, "reason", "Planned network maintenance");
        mvc.perform(jsonBody(as("kavya.reddy", post("/api/alerts/bulk")), request)).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("ananya.bose", post("/api/alerts/bulk")), request)).andExpect(status().isForbidden());

        // The reason is required for SUPPRESS; minutes 5..10080.
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/bulk")), Map.of("ids", ids, "action", "SUPPRESS", "minutes", 30)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/bulk")), Map.of("ids", ids, "action", "SUPPRESS", "reason", "    ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/bulk")), Map.of("ids", ids, "action", "SUPPRESS", "reason", "no")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/bulk")), Map.of("ids", ids, "action", "SUPPRESS", "minutes", 2, "reason", "Too short")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("minutes"));
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/bulk")), Map.of("ids", ids, "action", "SUPPRESS", "minutes", 10_081, "reason", "Too long")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("minutes"));
        assertThat(getJson("riya.varma", "/api/alerts/" + open).at("/alert/status").asText()).isEqualTo("OPEN"); // nothing changed so far

        long bulkAudits = jdbc.queryForObject("select count(*) from audit_logs where action = 'ALERTS_BULK_SUPPRESS'", Long.class);
        JsonNode result = body(mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/bulk")), request)).andExpect(status().isOk()).andReturn());
        assertThat(result.get("processed").asInt()).isEqualTo(2);
        assertThat(result.get("skipped").asInt()).isEqualTo(3);
        assertThat(result.get("messages")).hasSize(3);
        assertThat(result.get("messages").toString()).contains("is RESOLVED", "is SUPPRESSED", "Alert 999999 was not found");

        for (long id : List.of(open, acked)) {
            JsonNode a = getJson("riya.varma", "/api/alerts/" + id);
            assertThat(a.at("/alert/status").asText()).isEqualTo("SUPPRESSED");
            assertThat(a.get("suppressedUntil").isNull()).isFalse();
            assertThat(a.at("/alert/nextEscalationAt").isNull()).isTrue();
            assertThat(a.get("allowedActions").toString()).contains("UNSUPPRESS");
            List<JsonNode> sup = new java.util.ArrayList<>();
            getJson("riya.varma", "/api/alerts/" + id + "/history").forEach(e -> {
                if (e.get("type").asText().equals("SUPPRESSED")) {
                    sup.add(e);
                }
            });
            assertThat(sup).hasSize(1);
            assertThat(sup.get(0).get("note").asText()).isEqualTo("Planned network maintenance");
            assertThat(sup.get(0).at("/details/minutes").asInt()).isEqualTo(30);
            assertThat(sup.get(0).get("actorName").asText()).isEqualTo("Riya Varma");
            assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ALERT_SUPPRESSED' and entity_id = ?", Long.class,
                    a.at("/alert/number").asText())).isEqualTo(1);
        }
        assertThat(getJson("riya.varma", "/api/alerts/" + resolved).at("/alert/status").asText()).isEqualTo("RESOLVED");
        assertThat(getJson("riya.varma", "/api/alerts/" + suppressed + "/history").findValuesAsText("type")
                .stream().filter("SUPPRESSED"::equals).count()).isEqualTo(1); // the already suppressed alert was not touched again
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ALERTS_BULK_SUPPRESS'", Long.class)).isEqualTo(bulkAudits + 1);

        // Without minutes: suppressed until resolved or unsuppressed; unsuppress works as for a single suppression.
        JsonNode openEnded = body(mvc.perform(jsonBody(as("dev.nair", post("/api/alerts/bulk")), Map.of("ids", List.of(untilResolved), "action",
                "SUPPRESS", "reason", "Flapping check, owner informed"))).andExpect(status().isOk()).andReturn());
        assertThat(openEnded.get("processed").asInt()).isEqualTo(1);
        assertThat(openEnded.get("skipped").asInt()).isZero();
        JsonNode u = getJson("riya.varma", "/api/alerts/" + untilResolved);
        assertThat(u.at("/alert/status").asText()).isEqualTo("SUPPRESSED");
        assertThat(u.get("suppressedUntil").isNull()).isTrue();
        mvc.perform(as("riya.varma", post("/api/alerts/" + untilResolved + "/unsuppress"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.status").value("OPEN"));
    }

    @Test
    void suppressionAndRoutingRules() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        // Validation and permissions.
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/suppression-rules")), Map.of("name", "Nope", "match", Map.of("labels", Map.of("a", "b")))))
                .andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("admin", post("/api/alerts/suppression-rules")), Map.of("name", "Everything"))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("admin", post("/api/alerts/suppression-rules")), Map.of("name", "Backwards", "startsAt", "2026-12-02T00:00:00Z",
                "endsAt", "2026-12-01T00:00:00Z"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("endsAt"));
        mvc.perform(jsonBody(as("admin", post("/api/alerts/suppression-rules")), Map.of("name", "Half daily", "dailyStart", "01:00",
                "match", Map.of("labels", Map.of("a", "b"))))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("admin", post("/api/alerts/suppression-rules")), Map.of("name", "Bad time", "dailyStart", "25:00", "dailyEnd", "26:00",
                "match", Map.of("labels", Map.of("a", "b"))))).andExpect(status().isBadRequest());

        JsonNode rule = body(mvc.perform(jsonBody(as("admin", post("/api/alerts/suppression-rules")), Map.of("name", "Suppress test " + run,
                "match", Map.of("labels", Map.of("suppressRun", run), "severities", List.of("WARNING", "INFO"))))).andExpect(status().isCreated()).andReturn());
        assertThat(rule.get("activeNow").asBoolean()).isTrue();
        JsonNode future = body(mvc.perform(jsonBody(as("admin", post("/api/alerts/suppression-rules")), Map.of("name", "Future window " + run,
                "match", Map.of("labels", Map.of("futureRun", run)), "startsAt", Instant.now().plus(Duration.ofDays(3)).toString(),
                "endsAt", Instant.now().plus(Duration.ofDays(4)).toString()))).andExpect(status().isCreated()).andReturn());
        assertThat(future.get("activeNow").asBoolean()).isFalse();

        JsonNode warning = manual("riya.varma", alertBody("Backup noise " + run, "WARNING", Map.of("suppressRun", run)));
        assertThat(warning.at("/alert/status").asText()).isEqualTo("SUPPRESSED");
        assertThat(warning.get("suppressionRuleName").asText()).isEqualTo("Suppress test " + run);
        assertThat(warning.at("/alert/teamId").isNull()).isTrue(); // suppressed alerts are not routed
        JsonNode major = manual("riya.varma", alertBody("Backup failed " + run, "MAJOR", Map.of("suppressRun", run)));
        assertThat(major.at("/alert/status").asText()).isEqualTo("OPEN"); // severity not matched
        JsonNode notYet = manual("riya.varma", alertBody("Future noise " + run, "WARNING", Map.of("futureRun", run)));
        assertThat(notYet.at("/alert/status").asText()).isEqualTo("OPEN"); // window not open yet
        JsonNode rules = getJson("ananya.bose", "/api/alerts/suppression-rules");
        for (JsonNode r : rules) {
            if (r.get("id").asLong() == rule.get("id").asLong()) {
                assertThat(r.get("matchCount").asLong()).isEqualTo(1);
            }
        }
        // Disabling the rule: the next occurrence of the suppressed alert reopens it.
        mvc.perform(jsonBody(as("admin", put("/api/alerts/suppression-rules/" + rule.get("id").asLong())), Map.of("name", "Suppress test " + run,
                "enabled", false, "match", Map.of("labels", Map.of("suppressRun", run), "severities", List.of("WARNING", "INFO")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        JsonNode again = manual("riya.varma", alertBody("Backup noise " + run, "WARNING", Map.of("suppressRun", run)));
        assertThat(again.at("/alert/id").asLong()).isEqualTo(warning.at("/alert/id").asLong());
        assertThat(again.at("/alert/status").asText()).isEqualTo("OPEN");
        mvc.perform(as("admin", delete("/api/alerts/suppression-rules/" + future.get("id").asLong()))).andExpect(status().isNoContent());
        mvc.perform(as("admin", delete("/api/alerts/suppression-rules/" + future.get("id").asLong()))).andExpect(status().isNotFound());

        // Routing: team assignment and conversion from a minimum severity.
        mvc.perform(jsonBody(as("admin", post("/api/alerts/routing-rules")), Map.of("name", "Nothing to do " + run))).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/routing-rules")), Map.of("name", "x " + run, "teamId", teamId("NET-OPS"))))
                .andExpect(status().isForbidden());
        JsonNode routing = body(mvc.perform(jsonBody(as("admin", post("/api/alerts/routing-rules")), Map.of("name", "Route test " + run, "sortOrder", 5,
                "match", Map.of("labels", Map.of("routeRun", run)), "teamId", teamId("NET-OPS"), "createIncident", true, "incidentMinSeverity", "MAJOR")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(routing.get("teamName").asText()).isEqualTo("Network Operations");
        String routedTitle = "Firewall sessions near limit " + run;
        JsonNode routedMajor = manual("riya.varma", alertBody(routedTitle, "MAJOR", Map.of("routeRun", run)));
        assertThat(routedMajor.at("/alert/teamName").asText()).isEqualTo("Network Operations");
        assertThat(routedMajor.get("routingRuleName").asText()).isEqualTo("Route test " + run);
        assertThat(routedMajor.at("/alert/incidentNumber").asText()).startsWith("INC-");
        assertThat(routedMajor.get("history").findValuesAsText("type")).contains("ROUTED", "CONVERTED");
        assertThat(notifications("arjun.kapoor", "ALERT_ROUTED", routedMajor.at("/alert/number").asText())).isEqualTo(1);
        JsonNode routedWarning = manual("riya.varma", alertBody("Firewall CPU warm " + run, "WARNING", Map.of("routeRun", run)));
        assertThat(routedWarning.at("/alert/teamName").asText()).isEqualTo("Network Operations");
        assertThat(routedWarning.at("/alert/incidentId").isNull()).isTrue(); // below the rule's minimum severity
        assertThat(getJson("riya.varma", "/api/alerts/routing-rules").toString()).contains("Route test " + run);
        mvc.perform(as("admin", delete("/api/alerts/routing-rules/" + routing.get("id").asLong()))).andExpect(status().isNoContent());
        JsonNode unrouted = manual("riya.varma", alertBody("Firewall CPU hot " + run, "WARNING", Map.of("routeRun", run)));
        assertThat(unrouted.at("/alert/teamId").isNull()).isTrue();
        assertThat(getJson("riya.varma", "/api/alerts?scope=unassigned&search=" + run).get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void unacknowledgedAlertsEscalateExactlyOncePerLevel() throws Exception {
        // The built-in team-lead policy is the fallback for teams without an active on-call schedule (Release 3 BE-4 seeds one for every IT
        // team), so the demo NET-OPS schedule is switched off for this test; OnCallIT covers escalation through the on-call policy.
        jdbc.update("update oncall_schedules s set active = false from teams t where t.id = s.team_id and t.code = 'NET-OPS' and s.is_demo");
        try {
            teamLeadEscalation();
        } finally {
            jdbc.update("update oncall_schedules s set active = true from teams t where t.id = s.team_id and t.code = 'NET-OPS' and s.is_demo");
        }
    }

    private void teamLeadEscalation() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long rule = body(mvc.perform(jsonBody(as("admin", post("/api/alerts/routing-rules")), Map.of("name", "Escalation test " + run, "sortOrder", 6,
                "match", Map.of("labels", Map.of("escRun", run)), "teamId", teamId("NET-OPS")))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        JsonNode a = manual("riya.varma", alertBody("Uplink errors " + run, "MAJOR", Map.of("escRun", run)));
        long id = a.at("/alert/id").asLong();
        assertThat(a.at("/alert/nextEscalationAt").isNull()).isFalse();
        assertThat(a.get("escalationPolicy").asText()).isEqualTo("team-lead");
        JsonNode quiet = manual("riya.varma", alertBody("Uplink jitter " + run, "WARNING", Map.of("escRun", run)));
        assertThat(quiet.at("/alert/nextEscalationAt").isNull()).isTrue(); // below alert.escalation.min_severity
        JsonNode acked = manual("riya.varma", alertBody("Uplink flaps " + run, "CRITICAL", Map.of("escRun", run)));
        mvc.perform(as("arjun.kapoor", post("/api/alerts/" + acked.at("/alert/id").asLong() + "/acknowledge"))).andExpect(status().isOk());

        escalation.runEscalations();
        assertThat(getJson("riya.varma", "/api/alerts/" + id).at("/alert/escalationLevel").asInt()).isZero();
        String number = a.at("/alert/number").asText();
        assertThat(notifications("arjun.kapoor", "ALERT_ESCALATED", number)).isZero();

        testClock().advance(Duration.ofMinutes(16));
        escalation.runEscalations();
        escalation.runEscalations(); // a second run in the same minute does not escalate again
        JsonNode level1 = getJson("riya.varma", "/api/alerts/" + id);
        assertThat(level1.at("/alert/escalationLevel").asInt()).isEqualTo(1);
        assertThat(level1.get("history").findValuesAsText("type").stream().filter("ESCALATED"::equals).count()).isEqualTo(1);
        assertThat(notifications("arjun.kapoor", "ALERT_ESCALATED", number)).isEqualTo(1); // team lead
        assertThat(notifications("kiran.rao", "ALERT_ESCALATED", number)).isEqualTo(1); // manager of IT-NET
        assertThat(notifications("sunita.joshi", "ALERT_ESCALATED", number)).isZero();
        assertThat(getJson("riya.varma", "/api/alerts/" + acked.at("/alert/id").asLong()).at("/alert/escalationLevel").asInt()).isZero();

        testClock().advance(Duration.ofMinutes(16));
        escalation.runEscalations();
        assertThat(getJson("riya.varma", "/api/alerts/" + id).at("/alert/escalationLevel").asInt()).isEqualTo(2);
        assertThat(notifications("sunita.joshi", "ALERT_ESCALATED", number)).isEqualTo(1); // incident managers at level 2
        assertThat(notifications("arjun.kapoor", "ALERT_ESCALATED", number)).isEqualTo(1);
        assertThat(getJson("riya.varma", "/api/alerts/" + id).at("/alert/nextEscalationAt").isNull()).isTrue(); // last level
        testClock().advance(Duration.ofMinutes(30));
        escalation.runEscalations();
        assertThat(getJson("riya.varma", "/api/alerts/" + id).at("/alert/escalationLevel").asInt()).isEqualTo(2);

        mvc.perform(as("riya.varma", post("/api/alerts/escalations/run"))).andExpect(status().isForbidden());
        mvc.perform(as("admin", post("/api/alerts/escalations/run"))).andExpect(status().isOk()).andExpect(jsonPath("$.escalated").isNumber());
        mvc.perform(as("admin", delete("/api/alerts/routing-rules/" + rule))).andExpect(status().isNoContent());
    }

    @Test
    void legacyInboundIntegrationCreatesAlertsAndIncidents() throws Exception {
        JsonNode saved = body(mvc.perform(jsonBody(as("admin", post("/api/integrations")), Map.of("name", unique("Uptime robot"), "type",
                "MONITORING_INBOUND", "enabled", true))).andExpect(status().isCreated()).andReturn());
        long id = saved.at("/integration/id").asLong();
        String token = saved.get("inboundToken").asText();
        String title = unique("Endpoint provisioning queue backlog");
        Map<String, Object> alert = Map.of("title", title, "description", "Queue depth exceeded threshold 3 times", "severity", "critical", "service", "EUC",
                "source", "uptime");
        mvc.perform(jsonBody(post("/api/integrations/inbound/" + id), alert).header("X-Nexus-Token", "wrong")).andExpect(status().isUnauthorized());
        JsonNode first = body(mvc.perform(jsonBody(post("/api/integrations/inbound/" + id), alert).header("X-Nexus-Token", token))
                .andExpect(status().isAccepted()).andReturn());
        assertThat(first.get("incidentNumber").asText()).startsWith("INC-");
        assertThat(first.get("duplicate").asBoolean()).isFalse();
        assertThat(first.get("alertNumber").asText()).startsWith("ALR-");
        assertThat(first.get("alertStatus").asText()).isEqualTo("OPEN");
        JsonNode incident = getJson("riya.varma", "/api/incidents?search=" + first.get("incidentNumber").asText()).at("/content/0");
        assertThat(incident.get("number").asText()).isEqualTo(first.get("incidentNumber").asText());
        assertThat(incident.get("priority").asText()).isEqualTo("CRITICAL"); // critical → impact HIGH, urgency HIGH (as before)
        JsonNode detail = getJson("riya.varma", "/api/incidents/" + incident.get("id").asLong());
        assertThat(detail.get("description").asText()).contains("Received from").contains("severity critical");
        assertThat(detail.get("serviceName").asText()).isEqualTo("End-User Devices");
        // The same alert again: deduplicated into the alert, answered with its incident.
        JsonNode second = body(mvc.perform(jsonBody(post("/api/integrations/inbound/" + id), alert).header("X-Nexus-Token", token))
                .andExpect(status().isAccepted()).andReturn());
        assertThat(second.get("incidentNumber").asText()).isEqualTo(first.get("incidentNumber").asText());
        assertThat(second.get("duplicate").asBoolean()).isTrue();
        assertThat(second.get("alertNumber").asText()).isEqualTo(first.get("alertNumber").asText());
        assertThat(second.get("occurrences").asInt()).isEqualTo(2);
        JsonNode a = getJson("riya.varma", "/api/alerts/" + first.get("alertId").asLong());
        assertThat(a.at("/alert/sourceKind").asText()).isEqualTo("INTEGRATION");
        assertThat(a.get("integrationId").asLong()).isEqualTo(id);
        assertThat(a.at("/alert/severity").asText()).isEqualTo("CRITICAL");
    }

    @Test
    void alertReceivedWorkflowConvertsMatchingAlerts() throws Exception {
        String name = "Probe" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> definition = Map.of("conditions", Map.of("match", "ALL", "rules", List.of(
                        Map.of("field", "alertName", "operator", "EQUALS", "value", name),
                        Map.of("field", "alertSeverity", "operator", "IN", "value", "MAJOR,CRITICAL"))),
                "actions", List.of(Map.of("type", "CREATE_INCIDENT", "params", Map.of("urgency", "HIGH")),
                        Map.of("type", "ADD_COMMENT", "params", Map.of("message", "Workflow saw {{number}} ({{severity}})"))));
        Map<String, Object> wf = new HashMap<>(Map.of("name", "Alert to incident " + name, "triggerEvent", "ALERT_RECEIVED", "active", true,
                "maxExecutionsPerHour", 100, "maxRetries", 0, "retryBackoffSeconds", 30, "definition", definition));
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), wf)).andExpect(status().isCreated());
        Map<String, Object> wrongTrigger = new HashMap<>(wf);
        wrongTrigger.put("name", "Wrong trigger " + name);
        wrongTrigger.put("triggerEvent", "INCIDENT_CREATED");
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), wrongTrigger)).andExpect(status().isBadRequest());
        JsonNode meta = getJson("priya.raman", "/api/workflows/meta");
        assertThat(meta.get("triggers").toString()).contains("ALERT_RECEIVED");
        assertThat(meta.get("fields").findValuesAsText("field")).contains("alertSeverity", "alertSource", "alertName", "ciName");
        assertThat(meta.get("actions").findValuesAsText("type")).contains("CREATE_INCIDENT");

        JsonNode minor = manual("riya.varma", alertBody("Probe minor " + name, "MINOR", Map.of("alertname", name)));
        JsonNode major = manual("riya.varma", alertBody("Probe major " + name, "MAJOR", Map.of("alertname", name)));
        long majorId = major.at("/alert/id").asLong();
        await(() -> {
            try {
                return !getJson("riya.varma", "/api/alerts/" + majorId).at("/alert/incidentId").isNull();
            } catch (Exception e) {
                return false;
            }
        }, "workflow converts the major alert");
        JsonNode converted = getJson("riya.varma", "/api/alerts/" + majorId);
        assertThat(converted.get("history").findValuesAsText("type")).contains("CONVERTED");
        await(() -> {
            try {
                return getJson("riya.varma", "/api/alerts/" + majorId).get("history").findValuesAsText("type").contains("COMMENT");
            } catch (Exception e) {
                return false;
            }
        }, "workflow comment on the alert");
        JsonNode executions = getJson("priya.raman", "/api/workflows/executions?size=50");
        boolean found = false;
        for (JsonNode e : executions.get("content")) {
            if (e.get("alertId").asLong() == majorId) {
                found = true;
                assertThat(e.get("triggerEvent").asText()).isEqualTo("ALERT_RECEIVED");
            }
        }
        assertThat(found).isTrue();
        Thread.sleep(300);
        assertThat(getJson("riya.varma", "/api/alerts/" + minor.at("/alert/id").asLong()).at("/alert/incidentId").isNull()).isTrue();
    }

    @Test
    void demoPayrollAlertGroupMyWorkAndExport() throws Exception {
        JsonNode group = body(mvc.perform(as("riya.varma", get("/api/alerts").param("groupKey", "{}:{service=\"payroll\"}").param("size", "50")))
                .andExpect(status().isOk()).andReturn());
        assertThat(group.get("totalElements").asInt()).isEqualTo(3);
        List<String> incidents = group.get("content").findValuesAsText("incidentNumber");
        assertThat(incidents).hasSize(3).allMatch(n -> n.equals(incidents.get(0)) && n.startsWith("INC-"));
        assertThat(group.get("content").findValuesAsText("ciName")).contains("payroll-app-01", "payroll-app-02", "payroll-db-primary");
        long incidentId = group.at("/content/0/incidentId").asLong();
        assertThat(getJson("riya.varma", "/api/alerts?incidentId=" + incidentId).get("totalElements").asInt()).isGreaterThanOrEqualTo(4); // + CloudWatch ALB
        JsonNode groups = getJson("riya.varma", "/api/alerts/groups");
        assertThat(groups.findValuesAsText("groupKey")).contains("{}:{service=\"payroll\"}");
        // Suppressed demo alert and its rule; SNS demo source confirmed.
        assertThat(getJson("riya.varma", "/api/alerts?status=SUPPRESSED&search=payroll-db-replica").get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(getJson("admin", "/api/alerts/sources").findValuesAsText("provider")).contains("PROMETHEUS", "GRAFANA", "CLOUDWATCH", "GENERIC");
        // My Work: alerts of my teams (Riya is in Payroll & HR Apps).
        JsonNode work = getJson("riya.varma", "/api/my-work?limit=50");
        assertThat(work.at("/alerts/total").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(work.at("/alerts/items").findValuesAsText("teamName")).allMatch("Payroll & HR Apps"::equals);
        mvc.perform(as("kavya.reddy", get("/api/my-work"))).andExpect(status().isForbidden());
        // Webhook paths are public but unauthenticated callers cannot reach the inbox.
        mvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/alerts/webhooks/1").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
}

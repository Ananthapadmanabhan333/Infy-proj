package com.nexus360.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Correlation groups: alerts on CMDB-related components within the window are grouped with reasons (time, topology, deployment
 * proximity), a suggested root component, conversion to an incident, and the human actions split, merge and dismiss.
 */
class CorrelationIT extends ObservabilityTestSupport {

    private long alert(String title, Long serviceId, Long ciId, String severity) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", title);
        req.put("severity", severity);
        if (serviceId != null) {
            req.put("serviceId", serviceId);
        }
        if (ciId != null) {
            req.put("ciId", ciId);
        }
        req.put("labels", Map.of("team", "corr-" + title.hashCode()));
        JsonNode r = body(mvc.perform(jsonBody(as("riya.varma", post("/api/alerts")), req)).andExpect(status().isCreated()).andReturn());
        return r.at("/alert/id").asLong();
    }

    private Long groupOf(long alertId) {
        List<Long> g = jdbc.queryForList("select group_id from correlation_group_members where alert_id = ?", Long.class, alertId);
        return g.isEmpty() ? null : g.get(0);
    }

    @Test
    void relatedAlertsAreGroupedWithReasonsAndRootAndCanBeConverted() throws Exception {
        String code = ("COR" + uid()).toUpperCase();
        long[] svc = service(code);
        long db = ci("corr-db-" + uid(), "DATABASE");
        long app = ci("corr-app-" + uid(), "APPLICATION");
        relationship(svc[1], app, "DEPENDS_ON");
        relationship(app, db, "DEPENDS_ON");
        long unrelatedCi = ci("corr-unrelated-" + uid(), "SERVER");
        // A deployment of the service shortly before the alerts.
        mvc.perform(jsonBody(as("anil.kumar", post("/api/deployments")), Map.of("serviceId", svc[0], "environment", "PRODUCTION", "version", "9.9." + uid().length(),
                "status", "SUCCEEDED"))).andExpect(status().isCreated());

        // MAJOR, not CRITICAL: the demo ALERT_RECEIVED workflow turns critical alerts into incidents on its own.
        long dbAlert = alert("DB connections exhausted " + uid(), null, db, "MAJOR");
        long appAlert = alert("App latency high " + uid(), null, app, "MAJOR");
        long svcAlert = alert("Service errors " + uid(), svc[0], null, "MAJOR");
        long other = alert("Disk full elsewhere " + uid(), null, unrelatedCi, "MINOR");

        Long group = groupOf(dbAlert);
        assertThat(group).isNotNull();
        assertThat(groupOf(appAlert)).isEqualTo(group);
        assertThat(groupOf(svcAlert)).isEqualTo(group);
        assertThat(groupOf(other)).isNotEqualTo(group);

        JsonNode g = getJson("ananya.bose", "/api/observability/correlation-groups/" + group);
        assertThat(g.get("status").asText()).isEqualTo("OPEN");
        assertThat(g.get("memberCount").asInt()).isEqualTo(3);
        List<String> codes = new ArrayList<>();
        g.get("reasons").forEach(r -> codes.add(r.get("code").asText()));
        assertThat(codes).contains("TIME_WINDOW", "TOPOLOGY", "DEPLOYMENT");
        assertThat(g.get("rootCiId").asLong()).isEqualTo(db);
        assertThat(g.get("rootReason").asText()).contains("depend on it");
        assertThat(g.get("score").asDouble()).isGreaterThanOrEqualTo(0.5);
        assertThat(g.get("members")).hasSize(3);
        assertThat(g.get("members").get(1).get("reasons").toString()).contains("TOPOLOGY");
        assertThat(g.get("highestSeverity").asText()).isEqualTo("MAJOR");
        assertThat(g.get("incidentId").isNull()).isTrue();
        assertThat(getJson("riya.varma", "/api/observability/correlation-groups?status=OPEN").toString()).contains("\"id\":" + group);

        // Auditors read but cannot act.
        mvc.perform(jsonBody(as("ananya.bose", post("/api/observability/correlation-groups/" + group + "/convert")), Map.of())).andExpect(status().isForbidden());

        JsonNode converted = body(mvc.perform(jsonBody(as("sunita.joshi", post("/api/observability/correlation-groups/" + group + "/convert")),
                Map.of("title", "Correlated outage " + code))).andExpect(status().isOk()).andReturn());
        long incidentId = converted.get("incidentId").asLong();
        assertThat(incidentId).isPositive();
        for (long a : List.of(dbAlert, appAlert, svcAlert)) {
            assertThat(jdbc.queryForObject("select incident_id from alerts where id = ?", Long.class, a)).isEqualTo(incidentId);
        }
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/observability/correlation-groups/" + group + "/convert")), Map.of())).andExpect(status().isConflict());

        // Split one member out, merge it back, then dismiss.
        long svcMember = jdbc.queryForObject("select id from correlation_group_members where alert_id = ?", Long.class, svcAlert);
        JsonNode split = body(mvc.perform(jsonBody(as("riya.varma", post("/api/observability/correlation-groups/" + group + "/split")),
                Map.of("memberIds", List.of(svcMember)))).andExpect(status().isOk()).andReturn());
        long newGroup = split.get("id").asLong();
        assertThat(split.get("memberCount").asInt()).isEqualTo(1);
        assertThat(split.get("reasons").toString()).contains("MANUAL");
        assertThat(getJson("riya.varma", "/api/observability/correlation-groups/" + group).get("memberCount").asInt()).isEqualTo(2);
        JsonNode merged = body(mvc.perform(jsonBody(as("riya.varma", post("/api/observability/correlation-groups/" + group + "/merge")),
                Map.of("groupIds", List.of(newGroup)))).andExpect(status().isOk()).andReturn());
        assertThat(merged.get("memberCount").asInt()).isEqualTo(3);
        assertThat(getJson("riya.varma", "/api/observability/correlation-groups/" + newGroup).get("status").asText()).isEqualTo("CLOSED");
        mvc.perform(jsonBody(as("riya.varma", post("/api/observability/correlation-groups/" + group + "/dismiss")), Map.of("reason", "x")))
                .andExpect(status().isBadRequest());
        JsonNode dismissed = body(mvc.perform(jsonBody(as("riya.varma", post("/api/observability/correlation-groups/" + group + "/dismiss")),
                Map.of("reason", "Known maintenance noise"))).andExpect(status().isOk()).andReturn());
        assertThat(dismissed.get("status").asText()).isEqualTo("DISMISSED");
        assertThat(dismissed.get("dismissedByName").asText()).isEqualTo("Riya Varma");
        mvc.perform(jsonBody(as("riya.varma", post("/api/observability/correlation-groups/" + group + "/split")), Map.of("memberIds", List.of(svcMember))))
                .andExpect(status().isConflict());
        // A new alert on the same component does not join the dismissed group.
        long later = alert("DB connections exhausted again " + uid(), null, db, "CRITICAL");
        assertThat(groupOf(later)).isNotEqualTo(group);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action in ('CORRELATION_GROUP_CONVERTED', 'CORRELATION_GROUP_SPLIT', "
                + "'CORRELATION_GROUP_MERGED', 'CORRELATION_GROUP_DISMISSED')", Long.class)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void groupsCanBeLinkedToAnExistingIncident() throws Exception {
        long db = ci("link-db-" + uid(), "DATABASE");
        long app = ci("link-app-" + uid(), "APPLICATION");
        relationship(app, db, "DEPENDS_ON");
        long a1 = alert("Link db " + uid(), null, db, "MAJOR");
        long a2 = alert("Link app " + uid(), null, app, "MAJOR");
        Long group = groupOf(a1);
        assertThat(group).isNotNull().isEqualTo(groupOf(a2));
        JsonNode incident = createIncident("riya.varma", Map.of("title", unique("Correlation link target"), "description", "Linked by the correlation test",
                "impact", "LOW", "urgency", "LOW"));
        long incidentId = incident.at("/incident/id").asLong();
        JsonNode linked = body(mvc.perform(jsonBody(as("riya.varma", post("/api/observability/correlation-groups/" + group + "/link")),
                Map.of("incidentId", incidentId))).andExpect(status().isOk()).andReturn());
        assertThat(linked.get("incidentId").asLong()).isEqualTo(incidentId);
        assertThat(jdbc.queryForObject("select count(*) from alerts where incident_id = ? and id in (?, ?)", Long.class, incidentId, a1, a2)).isEqualTo(2);
        mvc.perform(jsonBody(as("riya.varma", post("/api/observability/correlation-groups/" + group + "/link")), Map.of("incidentId", 99_999_999)))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("rohan.kulkarni", post("/api/observability/correlation-groups/" + group + "/link")), Map.of("incidentId", incidentId)))
                .andExpect(status().isForbidden());
    }
}

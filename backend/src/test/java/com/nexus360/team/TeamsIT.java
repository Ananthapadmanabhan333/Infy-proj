package com.nexus360.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Teams (docs/RELEASE3_SPEC.md §4): CRUD, members, permissions, routing through teams and the workflow team action. */
class TeamsIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long userId(String user) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email(user));
    }

    private long departmentId(String code) {
        return jdbc.queryForObject("select id from departments where code = ?", Long.class, code);
    }

    private long teamId(String code) {
        return jdbc.queryForObject("select id from teams where code = ?", Long.class, code);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    @Test
    void demoTeamsAreSeededWithMembersAndServiceDefaults() throws Exception {
        JsonNode teams = getJson("riya.varma", "/api/teams");
        assertThat(teams.findValuesAsText("name")).contains("Payroll & HR Apps", "Collaboration Apps", "Network Operations",
                "Service Desk L1", "Database Operations", "Identity & Security", "Desktop Support", "Facilities");
        JsonNode payroll = getJson("riya.varma", "/api/teams/" + teamId("PAYROLL-HR"));
        assertThat(payroll.get("departmentName").asText()).isEqualTo("Enterprise Applications");
        assertThat(payroll.get("departmentDefault").asBoolean()).isTrue();
        assertThat(payroll.get("leadName").asText()).isEqualTo("Riya Varma");
        assertThat(payroll.get("demo").asBoolean()).isTrue();
        assertThat(payroll.get("members").findValuesAsText("email")).contains(email("riya.varma"), email("dev.nair"));
        assertThat(payroll.at("/members/0/memberRole").asText()).isEqualTo("LEAD");
        assertThat(jdbc.queryForObject("select count(*) from teams where is_demo = false and code in ('PAYROLL-HR','NET-OPS')", Long.class)).isZero();

        JsonNode service = getJson("riya.varma", "/api/services/" + jdbc.queryForObject("select id from business_services where code = 'EMAIL'", Long.class));
        assertThat(service.get("defaultTeamName").asText()).isEqualTo("Collaboration Apps");
        // Demo incidents seeded before teams existed were routed to a team of their department.
        assertThat(jdbc.queryForObject("select count(*) from incidents where is_demo and department_id is not null and team_id is null "
                + "and created_at < (select min(created_at) from teams where is_demo)", Long.class)).isZero();
        assertThat(getJson("deepa.nair", "/api/teams/mine").findValuesAsText("code")).containsExactly("DESKTOP");

        mvc.perform(as("amit.shah", get("/api/teams"))).andExpect(status().isForbidden());
    }

    @Test
    void teamsAreCreatedUpdatedAndMembersManagedByTeamAdmins() throws Exception {
        String code = "QA-" + suffix();
        Map<String, Object> request = new HashMap<>(Map.of("code", code, "name", "QA Team " + code, "departmentId", departmentId("IT-APPS"),
                "leadId", userId("meera.sharma"), "description", "Test team"));
        // Employees, agents and incident managers cannot maintain teams; IT managers only for departments they manage.
        mvc.perform(jsonBody(as("riya.varma", post("/api/teams")), request)).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/teams")), request)).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("kiran.rao", post("/api/teams")), request)).andExpect(status().isForbidden()); // manages IT-NET, IT-DB

        JsonNode created = body(mvc.perform(jsonBody(as("priya.raman", post("/api/teams")), request)).andExpect(status().isCreated()).andReturn());
        long id = created.get("id").asLong();
        assertThat(created.get("leadName").asText()).isEqualTo("Meera Sharma");
        assertThat(created.get("members").findValuesAsText("memberRole")).containsExactly("LEAD"); // the lead becomes a member
        assertThat(created.get("departmentDefault").asBoolean()).isFalse();
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/teams")), request)).andExpect(status().isConflict()); // duplicate code

        mvc.perform(jsonBody(as("priya.raman", post("/api/teams/" + id + "/members")), Map.of("userId", userId("amit.shah"))))
                .andExpect(status().isBadRequest()); // employees cannot be members
        mvc.perform(jsonBody(as("priya.raman", post("/api/teams/" + id + "/members")), Map.of("userId", userId("anil.kumar"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(2));
        mvc.perform(jsonBody(as("priya.raman", post("/api/teams/" + id + "/members")), Map.of("userId", userId("anil.kumar"))))
                .andExpect(status().isConflict());
        mvc.perform(jsonBody(as("priya.raman", put("/api/teams/" + id + "/members/" + userId("anil.kumar"))), Map.of("memberRole", "LEAD")))
                .andExpect(status().isOk());
        mvc.perform(as("priya.raman", delete("/api/teams/" + id + "/members/" + userId("meera.sharma")))).andExpect(status().isNoContent());
        JsonNode after = getJson("priya.raman", "/api/teams/" + id);
        assertThat(after.get("leadId").isNull()).isTrue(); // the removed member was the lead
        assertThat(after.get("members").findValuesAsText("email")).containsExactly(email("anil.kumar"));

        request.put("code", code + "X");
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/teams/" + id)), request)).andExpect(status().isBadRequest()); // code is immutable
        request.put("code", code);
        request.put("name", "QA Team renamed " + code);
        request.remove("leadId");
        request.put("active", false);
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/teams/" + id)), request)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.name").value("QA Team renamed " + code));
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action in ('TEAM_CREATED','TEAM_UPDATED','TEAM_MEMBER_ADDED',"
                + "'TEAM_MEMBER_UPDATED','TEAM_MEMBER_REMOVED') and entity_id = ?", Long.class, code)).isGreaterThanOrEqualTo(5);
    }

    @Test
    void oneDefaultTeamPerDepartment() throws Exception {
        String s = suffix();
        jdbc.update("insert into departments (code, name) values (?, ?)", "TD-" + s, "Team Default Test " + s);
        long dept = departmentId("TD-" + s);
        long a = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/teams")), Map.of("code", "TDA-" + s, "name", "Team A " + s,
                "departmentId", dept, "departmentDefault", true))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        long b = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/teams")), Map.of("code", "TDB-" + s, "name", "Team B " + s,
                "departmentId", dept, "departmentDefault", true))).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        assertThat(getJson("rajesh.iyer", "/api/teams/" + a).get("departmentDefault").asBoolean()).isFalse();
        assertThat(getJson("rajesh.iyer", "/api/teams/" + b).get("departmentDefault").asBoolean()).isTrue();
        assertThat(getJson("rajesh.iyer", "/api/teams?departmentId=" + dept)).hasSize(2);
    }

    @Test
    void incidentsAreRoutedThroughTheServiceTeamAndCanBeAssignedToATeamQueue() throws Exception {
        long email = jdbc.queryForObject("select id from business_services where code = 'EMAIL'", Long.class);
        JsonNode created = createIncident("kavya.reddy", Map.of("title", unique("Broadcast distribution list rejects quarterly bulletin"),
                "description", "The bulletin distribution list bounces the quarterly newsletter", "serviceId", email));
        JsonNode incident = created.get("incident");
        long id = incident.get("id").asLong();
        long collab = teamId("COLLAB-APPS");
        assertThat(incident.get("teamId").asLong()).isEqualTo(collab);
        assertThat(incident.get("teamName").asText()).isEqualTo("Collaboration Apps");
        assertThat(created.at("/assignment/teamName").asText()).isEqualTo("Collaboration Apps");
        // The least-loaded member of the team, support agents first.
        assertThat(List.of("Meera Sharma", "Dev Nair")).contains(incident.get("assigneeName").asText());

        // Assignment through another team: the department follows the team and a non-member cannot be the assignee.
        long payroll = teamId("PAYROLL-HR");
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + id + "/assign")), Map.of("teamId", payroll, "assigneeId", userId("meera.sharma"))))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + id + "/assign")), Map.of("teamId", payroll,
                "departmentId", departmentId("IT-NET")))).andExpect(status().isBadRequest());
        JsonNode queued = body(mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + id + "/assign")), Map.of("teamId", payroll)))
                .andExpect(status().isOk()).andReturn());
        assertThat(queued.get("teamName").asText()).isEqualTo("Payroll & HR Apps");
        assertThat(queued.get("assigneeId").isNull()).isTrue();
        assertThat(queued.get("status").asText()).isEqualTo("NEW");
        assertThat(queued.get("departmentName").asText()).isEqualTo("Enterprise Applications");
        assertThat(jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id where u.email = ? "
                + "and n.type = 'TEAM_QUEUE' and n.link = ?", Long.class, email("riya.varma"), "/incidents/" + id)).isEqualTo(1);

        // The team queue of a member lists it; filters by team and the "team" scope work.
        JsonNode myWork = getJson("riya.varma", "/api/my-work?limit=50");
        assertThat(myWork.at("/teamQueue/items").findValuesAsText("number")).contains(incident.get("number").asText());
        assertThat(getJson("riya.varma", "/api/incidents?teamId=" + payroll + "&size=100").at("/content").findValuesAsText("number"))
                .contains(incident.get("number").asText());
        assertThat(getJson("riya.varma", "/api/incidents?scope=team&size=100").at("/content").findValuesAsText("teamName"))
                .containsOnly("Payroll & HR Apps");
        assertThat(getJson("vikram.singh", "/api/incidents?scope=team&size=100").at("/content").findValuesAsText("number"))
                .doesNotContain(incident.get("number").asText());

        // Auto-assign picks the least-loaded member of the incident's team; picking a member keeps the team.
        JsonNode auto = body(mvc.perform(as("sunita.joshi", post("/api/incidents/" + id + "/auto-assign"))).andExpect(status().isOk()).andReturn());
        assertThat(List.of("Riya Varma", "Dev Nair")).contains(auto.get("assigneeName").asText());
        assertThat(auto.get("teamName").asText()).isEqualTo("Payroll & HR Apps");
        JsonNode toDev = body(mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + id + "/assign")),
                Map.of("assigneeId", userId("dev.nair")))).andExpect(status().isOk()).andReturn());
        assertThat(toDev.get("teamName").asText()).isEqualTo("Payroll & HR Apps");
        assertThat(getJson("sunita.joshi", "/api/incidents/" + id + "/history").findValuesAsText("field")).contains("team");
    }

    @Test
    void serviceRequestsCarryTheFulfilmentTeam() throws Exception {
        long vpn = jdbc.queryForObject("select id from catalog_items where name = 'VPN access'", Long.class);
        JsonNode request = body(mvc.perform(jsonBody(as("kavya.reddy", post("/api/service-requests")), Map.of("catalogItemId", vpn,
                "title", unique("VPN for travel"), "formData", Map.of("startDate", "2026-12-01", "justification", "Client visit"))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(request.get("teamName").asText()).isEqualTo("Network Operations");
        long id = request.get("id").asLong();
        mvc.perform(jsonBody(as("arjun.kapoor", post("/api/service-requests/" + id + "/assign")), Map.of())).andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("arjun.kapoor", post("/api/service-requests/" + id + "/assign")), Map.of("assigneeId", userId("vikram.singh"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assigneeName").value("Vikram Singh"))
                .andExpect(jsonPath("$.teamName").value("Network Operations"));
        mvc.perform(jsonBody(as("arjun.kapoor", post("/api/service-requests/" + id + "/assign")), Map.of("teamId", teamId("SD-L1"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.teamName").value("Service Desk L1"))
                .andExpect(jsonPath("$.departmentName").value("Service Desk"));
    }

    @Test
    void workflowsMatchTeamCodeAndAssignToATeam() throws Exception {
        String marker = "teamwf" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode meta = getJson("priya.raman", "/api/workflows/meta");
        assertThat(meta.get("fields").findValuesAsText("field")).contains("teamCode");
        assertThat(meta.get("actions").findValuesAsText("type")).contains("ASSIGN_TEAM");

        Map<String, Object> bad = workflow(marker, "NO-SUCH-TEAM");
        mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), bad)).andExpect(status().isBadRequest());
        long wf = body(mvc.perform(jsonBody(as("priya.raman", post("/api/workflows")), workflow(marker, "DB-OPS")))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        try {
            long email = jdbc.queryForObject("select id from business_services where code = 'EMAIL'", Long.class);
            long id = createIncident("kavya.reddy", Map.of("title", "Archive folder retention banner " + marker,
                    "description", "Retention banner text " + marker, "serviceId", email)).at("/incident/id").asLong();
            await(() -> "DB-OPS".equals(jdbc.queryForObject("select t.code from incidents i left join teams t on t.id = i.team_id where i.id = ?",
                    String.class, id)), "workflow assigned the incident to Database Operations");
            JsonNode incident = getJson("priya.raman", "/api/incidents/" + id);
            assertThat(incident.get("teamName").asText()).isEqualTo("Database Operations");
            assertThat(incident.get("assigneeName").asText()).isEqualTo("Farhan Ali");
        } finally {
            mvc.perform(jsonBody(as("priya.raman", post("/api/workflows/" + wf + "/activate")), Map.of("active", false))).andExpect(status().isOk());
        }
    }

    private static Map<String, Object> workflow(String marker, String teamCode) {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(Map.of("field", "title", "operator", "CONTAINS", "value", marker));
        rules.add(Map.of("field", "teamCode", "operator", "EQUALS", "value", "COLLAB-APPS"));
        return Map.of("name", "Team routing " + marker + teamCode, "triggerEvent", "INCIDENT_CREATED", "active", true, "maxExecutionsPerHour", 10,
                "maxRetries", 0, "retryBackoffSeconds", 5,
                "definition", Map.of("conditions", Map.of("match", "ALL", "rules", rules),
                        "actions", List.of(Map.of("type", "ASSIGN_TEAM", "params", Map.of("teamCode", teamCode)))));
    }
}

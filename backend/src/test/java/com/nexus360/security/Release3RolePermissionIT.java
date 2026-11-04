package com.nexus360.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import com.nexus360.user.Role;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Release 3 roles CHANGE_MANAGER and ASSET_MANAGER and the new groups (docs/RELEASE3_SPEC.md §2) on the real filter chain. */
class Release3RolePermissionIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void demoUsersCarryTheNewRoles() throws Exception {
        assertThat(getJson("anil.kumar", "/api/auth/me").get("role").asText()).isEqualTo("CHANGE_MANAGER");
        assertThat(getJson("anil.kumar", "/api/auth/me").get("departmentName").asText()).isEqualTo("Enterprise Applications");
        assertThat(getJson("deepa.nair", "/api/auth/me").get("role").asText()).isEqualTo("ASSET_MANAGER");
        assertThat(getJson("deepa.nair", "/api/auth/me").get("departmentName").asText()).isEqualTo("End User Computing");
        assertThat(jdbc.queryForObject("select count(*) from users where email in (?, ?) and is_demo", Long.class,
                email("anil.kumar"), email("deepa.nair"))).isEqualTo(2);
    }

    @Test
    void changeAndAssetManagersAreStaffButKeepNoExtraPowers() throws Exception {
        long id = createIncident("amit.shah", Map.of("title", unique("Docking station flickers external monitor"),
                "description", "External monitor flickers through the dock")).at("/incident/id").asLong();
        for (String user : new String[]{"anil.kumar", "deepa.nair"}) {
            // Staff: read operational data, work incidents, internal notes, My Work.
            mvc.perform(as(user, get("/api/sla/overview"))).andExpect(status().isOk());
            mvc.perform(as(user, get("/api/workflows"))).andExpect(status().isOk());
            mvc.perform(as(user, get("/api/my-work"))).andExpect(status().isOk());
            mvc.perform(jsonBody(as(user, post("/api/incidents/" + id + "/comments")), Map.of("body", "Work note from " + user, "internal", true)))
                    .andExpect(status().isCreated());
            mvc.perform(jsonBody(as(user, post("/api/incidents/" + id + "/escalate")), Map.of("reason", "Needs a closer look")))
                    .andExpect(status().isOk());
            // Not managers, administrators, approvers or exporters.
            mvc.perform(jsonBody(as(user, post("/api/incidents/" + id + "/major")), Map.of("major", true))).andExpect(status().isForbidden());
            mvc.perform(as(user, get("/api/analytics/export"))).andExpect(status().isForbidden());
            // Approval engine v2: the inbox is open to every user but only lists tasks routed to them (none here).
            mvc.perform(as(user, get("/api/approvals?type=SERVICE_REQUEST&status=PENDING"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
            mvc.perform(as(user, get("/api/audit-logs"))).andExpect(status().isForbidden());
            mvc.perform(as(user, get("/api/users"))).andExpect(status().isForbidden());
            mvc.perform(jsonBody(as(user, post("/api/teams")), Map.of("code", "X-" + user.length(), "name", "Nope", "departmentId", 1)))
                    .andExpect(status().isForbidden());
            mvc.perform(jsonBody(as(user, put("/api/status-page/settings")), Map.of("title", "x", "publicEnabled", false)))
                    .andExpect(status().isForbidden());
            mvc.perform(jsonBody(as(user, post("/api/reports/schedules")), Map.of())).andExpect(status().isForbidden());
        }
        assertThat(getJson("riya.varma", "/api/users/assignable").findValuesAsText("email")).contains(email("anil.kumar"), email("deepa.nair"));
    }

    @Test
    void administratorsCreateAccountsWithTheNewRoles() throws Exception {
        for (String role : new String[]{"CHANGE_MANAGER", "ASSET_MANAGER"}) {
            String local = "r3." + role.toLowerCase().replace('_', '.') + "." + UUID.randomUUID().toString().substring(0, 6);
            Map<String, Object> create = new HashMap<>(Map.of("email", local + "@demo.nexus360.io", "fullName", "Release Three " + role,
                    "role", role, "password", "Release3-Pass-2026"));
            mvc.perform(jsonBody(as("rajesh.iyer", post("/api/users")), create)).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value(role));
            JsonNode link = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/admin/invitations")), Map.of("email", "invite." + local + "@example.org",
                    "role", role, "departmentId", jdbc.queryForObject("select id from departments where code = 'IT-SD'", Long.class))))
                    .andExpect(status().isOk()).andReturn());
            assertThat(link.get("link").asText()).startsWith("/register?invite=");
        }
    }

    @Test
    void releaseThreeGroupsMatchTheSpecification() {
        assertThat(roles(Roles.STAFF)).isEqualTo(names(Role.STAFF));
        assertThat(roles(Roles.READERS)).isEqualTo(names(Role.READERS));
        assertThat(roles(Roles.READERS)).isEqualTo(Arrays.stream(Roles.READER_ROLES).collect(Collectors.toSet()));
        assertThat(roles(Roles.STAFF)).isEqualTo(Arrays.stream(Roles.STAFF_ROLES).collect(Collectors.toSet()));
        assertThat(roles(Roles.STAFF)).contains("CHANGE_MANAGER", "ASSET_MANAGER");
        assertThat(roles(Roles.CHANGE_EDITORS)).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "CHANGE_MANAGER");
        assertThat(roles(Roles.CHANGE_APPROVERS)).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_MANAGER", "CHANGE_MANAGER");
        assertThat(roles(Roles.DEPLOYMENT_EDITORS)).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "CHANGE_MANAGER");
        assertThat(roles(Roles.ASSET_EDITORS)).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "ASSET_MANAGER");
        assertThat(roles(Roles.CMDB_EDITORS)).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "ASSET_MANAGER", "IT_MANAGER");
        assertThat(roles(Roles.PROBLEM_EDITORS)).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER", "SUPPORT_AGENT");
        assertThat(roles(Roles.PROBLEM_OWNERS_CLOSE)).isEqualTo(names(Role.ADMIN_OR_MANAGER));
        assertThat(roles(Roles.ALERT_RESPONDERS)).isEqualTo(names(Role.STAFF));
        assertThat(roles(Roles.ALERT_CONFIG)).isEqualTo(names(Role.ADMINS));
        assertThat(roles(Roles.ONCALL_EDITORS)).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER");
        assertThat(roles(Roles.MAJOR_INCIDENT_COMMAND)).isEqualTo(names(Role.ADMIN_OR_MANAGER));
        assertThat(roles(Roles.PIR_APPROVERS)).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER");
        assertThat(roles(Roles.STATUS_PAGE_EDITORS)).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "INCIDENT_MANAGER");
        assertThat(roles(Roles.REPORT_SCHEDULERS)).isEqualTo(roles(Roles.REPORT_EXPORTERS));
        assertThat(roles(Roles.REPORT_EXPORTERS)).isEqualTo(names(Role.REPORT_EXPORTERS));
        assertThat(roles(Roles.TEAM_ADMINS)).containsExactlyInAnyOrder("SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER");
        // Release 2 groups are unchanged.
        assertThat(roles(Roles.ADMIN_OR_MANAGER)).isEqualTo(names(Role.ADMIN_OR_MANAGER));
        assertThat(roles(Roles.APPROVERS)).isEqualTo(names(Role.APPROVERS));
        assertThat(Role.CHANGE_MANAGER.label()).isEqualTo("Change Manager");
        assertThat(Role.ASSET_MANAGER.label()).isEqualTo("Asset Manager");
    }

    /** Role names in a {@code hasAnyRole('A','B')} / {@code hasRole('A')} expression. */
    private static Set<String> roles(String expression) {
        String inner = expression.substring(expression.indexOf('(') + 1, expression.lastIndexOf(')'));
        return Arrays.stream(inner.split(",")).map(s -> s.trim().replace("'", "")).collect(Collectors.toSet());
    }

    private static Set<String> names(Set<Role> roles) {
        return roles.stream().map(Enum::name).collect(Collectors.toSet());
    }
}

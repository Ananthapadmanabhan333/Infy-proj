package com.nexus360.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.audit.AuditLogRepository;
import com.nexus360.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Release 2 role groups (docs/RELEASE2_SPEC.md §1), checked on the real security filter chain and method security. */
class RolePermissionMatrixIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditLogRepository auditLogs;

    private long userId(String user) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email(user));
    }

    private long departmentId(String code) {
        return jdbc.queryForObject("select id from departments where code = ?", Long.class, code);
    }

    @Test
    void demoUsersCarryTheReleaseTwoRoles() throws Exception {
        Map<String, String> expected = Map.of("admin", "SYSTEM_ADMIN", "rajesh.iyer", "IT_ADMIN", "priya.raman", "IT_MANAGER",
                "sunita.joshi", "INCIDENT_MANAGER", "riya.varma", "SUPPORT_AGENT", "amit.shah", "EMPLOYEE", "ananya.bose", "AUDITOR");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            JsonNode me = getJson(e.getKey(), "/api/auth/me");
            assertThat(me.get("role").asText()).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(me.at("/organisation/name").asText()).as(e.getKey()).isNotBlank();
        }
        assertThat(jdbc.queryForObject("select count(*) from users where role in ('ADMIN','MANAGER','AGENT')", Long.class)).isZero();
    }

    @Test
    void settingsAndDemoToolsAreForSystemAdministratorsOnly() throws Exception {
        mvc.perform(as("admin", get("/api/admin/settings"))).andExpect(status().isOk());
        mvc.perform(as("rajesh.iyer", get("/api/admin/settings"))).andExpect(status().isForbidden());
        mvc.perform(as("priya.raman", get("/api/admin/settings"))).andExpect(status().isForbidden());
        mvc.perform(as("rajesh.iyer", get("/api/admin/ai"))).andExpect(status().isForbidden());
        mvc.perform(as("rajesh.iyer", post("/api/admin/demo/payroll-outage"))).andExpect(status().isForbidden());
        mvc.perform(as("priya.raman", post("/api/admin/demo/payroll-outage"))).andExpect(status().isForbidden());
    }

    @Test
    void configurationAndHealthAreForAdministrators() throws Exception {
        String code = "QA-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/departments")), Map.of("code", code, "name", "Matrix " + code)))
                .andExpect(status().isCreated());
        mvc.perform(jsonBody(as("priya.raman", post("/api/departments")), Map.of("code", code + "X", "name", "Matrix " + code + "X")))
                .andExpect(status().isForbidden());
        mvc.perform(as("rajesh.iyer", get("/api/admin/system-health"))).andExpect(status().isOk());
        mvc.perform(as("priya.raman", get("/api/admin/system-health"))).andExpect(status().isForbidden());
        mvc.perform(as("ananya.bose", get("/api/admin/system-health"))).andExpect(status().isForbidden());
        mvc.perform(as("priya.raman", get("/api/admin/incident-categories"))).andExpect(status().isOk());
        mvc.perform(as("ananya.bose", get("/api/admin/incident-categories"))).andExpect(status().isOk());
        mvc.perform(as("amit.shah", get("/api/admin/incident-categories"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("sunita.joshi", put("/api/admin/incident-categories")), List.of())).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("priya.raman", post("/api/sla/evaluate")), Map.of())).andExpect(status().isForbidden());
        mvc.perform(as("rajesh.iyer", get("/api/integrations"))).andExpect(status().isOk());
        mvc.perform(as("riya.varma", get("/api/integrations"))).andExpect(status().isForbidden());
    }

    @Test
    void itAdministratorsCannotCreateEditOrGrantSystemAdministrators() throws Exception {
        String local = "matrix." + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> create = new HashMap<>(Map.of("email", local + "@demo.nexus360.io", "fullName", "Matrix Tester",
                "role", "SYSTEM_ADMIN", "departmentId", departmentId("IT-SD"), "password", "Release2-Pass-2026"));
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/users")), create)).andExpect(status().isForbidden());
        create.put("role", "SUPPORT_AGENT");
        long id = body(mvc.perform(jsonBody(as("rajesh.iyer", post("/api/users")), create)).andExpect(status().isCreated()).andReturn())
                .get("id").asLong();

        Map<String, Object> update = new HashMap<>(Map.of("fullName", "Matrix Tester", "role", "SYSTEM_ADMIN",
                "departmentId", departmentId("IT-SD"), "active", true));
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/users/" + id)), update)).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/users/" + userId("admin"))),
                Map.of("fullName", "Aarav Mehta", "role", "SYSTEM_ADMIN", "active", true))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/users/" + userId("admin") + "/reset-password")),
                Map.of("newPassword", "Another-Pass-2026"))).andExpect(status().isForbidden());

        long rolesChanged = auditLogs.countByAction("USER_ROLE_CHANGED");
        update.put("role", "IT_MANAGER");
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/users/" + id)), update)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("IT_MANAGER"));
        assertThat(auditLogs.countByAction("USER_ROLE_CHANGED")).isEqualTo(rolesChanged + 1);
        Map<String, Object> details = auditLogs.findAll().stream().filter(a -> "USER_ROLE_CHANGED".equals(a.getAction())
                && String.valueOf(id).equals(a.getEntityId())).findFirst().orElseThrow().getDetails();
        assertThat(details).containsEntry("from", "SUPPORT_AGENT").containsEntry("to", "IT_MANAGER");

        long deactivated = auditLogs.countByAction("USER_DEACTIVATED");
        update.put("active", false);
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/users/" + id)), update)).andExpect(status().isOk());
        assertThat(auditLogs.countByAction("USER_DEACTIVATED")).isEqualTo(deactivated + 1);

        update.put("active", true);
        update.put("role", "SYSTEM_ADMIN");
        mvc.perform(jsonBody(as("admin", put("/api/users/" + id)), update)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SYSTEM_ADMIN"));
        assertThat(auditLogs.countByAction("USER_REACTIVATED")).isPositive();
        mvc.perform(as("priya.raman", get("/api/users"))).andExpect(status().isOk());
        mvc.perform(jsonBody(as("priya.raman", put("/api/users/" + id)), update)).andExpect(status().isForbidden());
    }

    @Test
    void workflowEditorsAreAdministratorsAndItManagers() throws Exception {
        String marker = "matrixwf" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> wf = Map.of("name", "Matrix " + marker, "triggerEvent", "INCIDENT_CREATED", "active", false,
                "maxExecutionsPerHour", 10, "maxRetries", 0, "retryBackoffSeconds", 5,
                "definition", Map.of("conditions", Map.of("match", "ALL", "rules", List.of(Map.of("field", "title", "operator", "CONTAINS", "value", marker))),
                        "actions", List.of(Map.of("type", "NOTIFY_ASSIGNEE", "params", Map.of()))));
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/workflows")), wf)).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("riya.varma", post("/api/workflows")), wf)).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/workflows")), wf)).andExpect(status().isCreated());
        mvc.perform(as("ananya.bose", get("/api/workflows"))).andExpect(status().isOk());
    }

    @Test
    void majorIncidentEscalationAuditAndExportGroups() throws Exception {
        long id = createIncident("amit.shah", Map.of("title", unique("Expense report attachment rejected"), "description", "Upload refused"))
                .at("/incident/id").asLong();
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + id + "/major")), Map.of("major", true))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("sunita.joshi", post("/api/incidents/" + id + "/major")), Map.of("major", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.major").value(true));
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/incidents/" + id + "/major")), Map.of("major", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.major").value(false));
        mvc.perform(jsonBody(as("riya.varma", post("/api/incidents/" + id + "/escalate")), Map.of("reason", "Finance blocked")))
                .andExpect(status().isOk());

        mvc.perform(as("priya.raman", get("/api/audit-logs"))).andExpect(status().isForbidden());
        mvc.perform(as("rajesh.iyer", get("/api/audit-logs"))).andExpect(status().isOk());
        mvc.perform(as("ananya.bose", get("/api/audit-logs"))).andExpect(status().isOk());
        mvc.perform(as("sunita.joshi", get("/api/incidents/export"))).andExpect(status().isOk());
        mvc.perform(as("riya.varma", get("/api/incidents/export"))).andExpect(status().isForbidden());

        // Approval engine v2: everyone may open the inbox, which only lists approvals routed to them.
        mvc.perform(as("riya.varma", get("/api/approvals?type=SERVICE_REQUEST&status=PENDING"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(as("rajesh.iyer", get("/api/approvals?type=SERVICE_REQUEST&status=PENDING"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0)); // IT admins are not approvers
        mvc.perform(as("sunita.joshi", get("/api/approvals"))).andExpect(status().isOk());
        mvc.perform(as("priya.raman", get("/api/approvals"))).andExpect(status().isOk());
        mvc.perform(as("admin", get("/api/approvals"))).andExpect(status().isOk());
        mvc.perform(as("admin", org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/incidents/" + id)))
                .andExpect(status().isNoContent());
    }
}

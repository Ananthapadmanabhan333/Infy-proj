package com.nexus360.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus360.audit.AuditLogRepository;
import com.nexus360.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class AuthAndAuthorizationIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditLogRepository auditLogs;

    @Test
    void loginIssuesAccessTokenAndHttpOnlyRefreshCookie() throws Exception {
        mvc.perform(jsonBody(post("/api/auth/login"), Map.of("email", email("neha.gupta"), "password", DEMO_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("SUPPORT_AGENT"))
                .andExpect(cookie().httpOnly(AuthController.REFRESH_COOKIE, true))
                .andExpect(cookie().path(AuthController.REFRESH_COOKIE, "/api/auth"));
    }

    @Test
    void wrongPasswordIsRejectedAndRepeatedFailuresLockTheAccount() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(jsonBody(post("/api/auth/login"), Map.of("email", email("pooja.iyer"), "password", "wrong-password-1")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password"));
        }
        mvc.perform(jsonBody(post("/api/auth/login"), Map.of("email", email("pooja.iyer"), "password", DEMO_PASSWORD)))
                .andExpect(status().isLocked());
        assertThat(auditLogs.countByAction("AUTH_ACCOUNT_LOCKED")).isPositive();
        jdbc.update("update users set locked_until = null, failed_login_attempts = 0 where email = ?", email("pooja.iyer"));
    }

    @Test
    void unknownEmailGivesTheSameGenericError() throws Exception {
        mvc.perform(jsonBody(post("/api/auth/login"), Map.of("email", "nobody@demo.nexus360.io", "password", "whatever-123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void refreshRotatesTokenAndReuseRevokesTheFamily() throws Exception {
        MvcResult login = mvc.perform(jsonBody(post("/api/auth/login"), Map.of("email", email("rahul.das"), "password", DEMO_PASSWORD)))
                .andReturn();
        Cookie first = login.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(first).isNotNull();

        mvc.perform(post("/api/auth/refresh").cookie(first)).andExpect(status().isForbidden()); // missing CSRF header

        MvcResult rotated = mvc.perform(post("/api/auth/refresh").cookie(first).header("X-Requested-With", "nexus360"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty()).andReturn();
        Cookie second = rotated.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // Outside the parallel-tab grace window, replaying the old token is treated as theft.
        jdbc.update("update refresh_tokens set revoked_at = revoked_at - interval '1 minute' where revoked_at is not null");
        mvc.perform(post("/api/auth/refresh").cookie(first).header("X-Requested-With", "nexus360")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(second).header("X-Requested-With", "nexus360")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointsRequireAToken() throws Exception {
        mvc.perform(get("/api/incidents")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Authentication required"));
        mvc.perform(get("/api/incidents").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void roleBasedAccessIsEnforcedOnTheServer() throws Exception {
        mvc.perform(as("amit.shah", get("/api/audit-logs"))).andExpect(status().isForbidden());
        mvc.perform(as("amit.shah", get("/api/users"))).andExpect(status().isForbidden());
        mvc.perform(as("amit.shah", get("/api/sla/overview"))).andExpect(status().isForbidden());
        mvc.perform(as("riya.varma", get("/api/admin/settings"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("riya.varma", post("/api/users")), Map.of("email", "x@demo.nexus360.io", "fullName", "X", "role", "SYSTEM_ADMIN",
                "password", "Abcdefgh-123"))).andExpect(status().isForbidden());
        mvc.perform(as("ananya.bose", get("/api/audit-logs"))).andExpect(status().isOk());
        mvc.perform(jsonBody(as("ananya.bose", post("/api/incidents/1/comments")), Map.of("body", "auditor note", "internal", false)))
                .andExpect(status().isForbidden());
        assertThat(auditLogs.countByAction("ACCESS_DENIED")).isPositive();
    }

    @Test
    void employeesOnlySeeTheirOwnIncidents() throws Exception {
        var mine = getJson("amit.shah", "/api/incidents?size=100");
        mine.get("content").forEach(i -> assertThat(i.get("requesterName").asText()).isEqualTo("Amit Shah"));
        Long foreign = jdbc.queryForObject("select i.id from incidents i join users u on u.id = i.requester_id "
                + "where u.email <> ? limit 1", Long.class, email("amit.shah"));
        mvc.perform(as("amit.shah", get("/api/incidents/" + foreign))).andExpect(status().isNotFound());
    }

    @Test
    void dataExportsAreRestrictedAndAudited() throws Exception {
        mvc.perform(as("amit.shah", get("/api/incidents/export"))).andExpect(status().isForbidden());
        long incidentsBefore = auditLogs.countByAction("INCIDENTS_EXPORTED");
        long analyticsBefore = auditLogs.countByAction("ANALYTICS_EXPORTED");
        mvc.perform(as("priya.raman", get("/api/incidents/export"))).andExpect(status().isOk());
        mvc.perform(as("ananya.bose", get("/api/analytics/export?days=30"))).andExpect(status().isOk());
        assertThat(auditLogs.countByAction("INCIDENTS_EXPORTED")).isEqualTo(incidentsBefore + 1);
        assertThat(auditLogs.countByAction("ANALYTICS_EXPORTED")).isEqualTo(analyticsBefore + 1);
    }

    @Test
    void auditLogIsAppendOnly() {
        assertThat(auditLogs.count()).isPositive();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update audit_logs set action = 'TAMPERED'"))
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("delete from audit_logs"))
                .hasMessageContaining("append-only");
    }
}

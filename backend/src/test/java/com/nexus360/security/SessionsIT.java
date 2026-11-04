package com.nexus360.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** Sessions and "remember me" (docs/RELEASE3_SPEC.md §4), with dedicated accounts so other tests' tokens stay valid. */
class SessionsIT extends IntegrationTest {

    private static final String PASSWORD = "Sessions-Pass-2026";

    @Autowired
    JdbcTemplate jdbc;

    /** A fresh staff account (sessions of shared demo users are left alone). */
    private String account() throws Exception {
        String email = "session." + UUID.randomUUID().toString().substring(0, 8) + "@demo.nexus360.io";
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/users")), Map.of("email", email, "fullName", "Session Tester", "role", "SUPPORT_AGENT",
                "departmentId", jdbc.queryForObject("select id from departments where code = 'IT-SD'", Long.class), "password", PASSWORD)))
                .andExpect(status().isCreated());
        return email;
    }

    private record Login(String accessToken, Cookie cookie, String setCookie) {}

    private Login login(String email, Boolean rememberMe, String userAgent) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("email", email, "password", PASSWORD));
        if (rememberMe != null) {
            body.put("rememberMe", rememberMe);
        }
        MvcResult result = mvc.perform(jsonBody(post("/api/auth/login"), body).header(HttpHeaders.USER_AGENT, userAgent))
                .andExpect(status().isOk()).andReturn();
        return new Login(json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText(),
                result.getResponse().getCookie(AuthController.REFRESH_COOKIE), result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
    }

    private int refresh(Cookie cookie) throws Exception {
        return mvc.perform(post("/api/auth/refresh").cookie(cookie).header("X-Requested-With", "nexus360")).andReturn().getResponse().getStatus();
    }

    @Test
    void rememberMeDecidesBetweenPersistentAndBrowserSessionCookies() throws Exception {
        String email = account();
        Login persistent = login(email, true, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0 Safari/537.36");
        assertThat(persistent.setCookie()).contains("Max-Age=604800").contains("HttpOnly").contains("Path=/api/auth");
        Login browserSession = login(email, false, "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) Version/17.0 Safari/605.1.15");
        assertThat(browserSession.setCookie()).doesNotContain("Max-Age").doesNotContain("Expires").contains("HttpOnly");
        Login legacy = login(email, null, "curl/8.4");
        assertThat(legacy.setCookie()).contains("Max-Age="); // omitted = remember me (Release 2 behaviour)

        // A refresh keeps the cookie type of the session.
        MvcResult rotated = mvc.perform(post("/api/auth/refresh").cookie(browserSession.cookie()).header("X-Requested-With", "nexus360"))
                .andExpect(status().isOk()).andReturn();
        assertThat(rotated.getResponse().getHeader(HttpHeaders.SET_COOKIE)).doesNotContain("Max-Age");
        Cookie next = rotated.getResponse().getCookie(AuthController.REFRESH_COOKIE);

        // Sessions without remember me end 12 hours after sign-in on the server, whatever the browser does.
        Long lifetime = jdbc.queryForObject("select round(extract(epoch from (s.expires_at - s.created_at)))::bigint from auth_sessions s "
                + "join users u on u.id = s.user_id where u.email = ? and not s.remember_me", Long.class, email);
        assertThat(lifetime).isEqualTo(Duration.ofHours(12).toSeconds());
        testClock().advance(Duration.ofHours(13));
        assertThat(refresh(next)).isEqualTo(401);
        assertThat(refresh(persistent.cookie())).isEqualTo(200);
    }

    @Test
    void usersListAndEndTheirSessions() throws Exception {
        String email = account();
        Login laptop = login(email, true, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36 Edg/130.0");
        Login phone = login(email, false, "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Version/17.0 Mobile Safari/604.1");
        Login tablet = login(email, true, "Mozilla/5.0 (Linux; Android 14) Firefox/131.0");

        JsonNode sessions = body(mvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + laptop.accessToken()))
                .andExpect(status().isOk()).andReturn());
        assertThat(sessions).hasSize(3);
        assertThat(sessions.findValuesAsText("device")).contains("Edge on Windows", "Safari on iOS", "Firefox on Android");
        JsonNode current = sessions.findParents("current").stream().filter(n -> n.get("current").asBoolean()).toList().get(0);
        assertThat(current.get("device").asText()).isEqualTo("Edge on Windows");
        assertThat(sessions.findValuesAsText("current").stream().filter("true"::equals).count()).isEqualTo(1);
        assertThat(current.get("ipAddress").asText()).isNotBlank();
        assertThat(current.get("lastUsedAt").asText()).isNotBlank();
        long phoneId = sessions.findParents("device").stream().filter(n -> n.get("device").asText().equals("Safari on iOS")).findFirst()
                .orElseThrow().get("id").asLong();
        assertThat(sessions.findParents("device").stream().filter(n -> n.get("id").asLong() == phoneId).findFirst().orElseThrow()
                .get("rememberMe").asBoolean()).isFalse();

        // Ending one session revokes its refresh-token family; other sessions keep working.
        mvc.perform(delete("/api/auth/sessions/" + phoneId).header("Authorization", "Bearer " + laptop.accessToken())).andExpect(status().isNoContent());
        assertThat(refresh(phone.cookie())).isEqualTo(401);
        assertThat(refresh(tablet.cookie())).isEqualTo(200);
        // Someone else's session is invisible.
        mvc.perform(as("riya.varma", delete("/api/auth/sessions/" + phoneId))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/auth/sessions/" + phoneId).header("Authorization", "Bearer " + laptop.accessToken())).andExpect(status().isNotFound());

        // "Sign out other sessions" keeps only the current one.
        mvc.perform(post("/api/auth/sessions/revoke-others").header("Authorization", "Bearer " + laptop.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(1));
        assertThat(refresh(laptop.cookie())).isEqualTo(200);
        JsonNode remaining = body(mvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + laptop.accessToken())).andReturn());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).get("current").asBoolean()).isTrue();

        // Ending the current session also clears the cookie.
        MvcResult self = mvc.perform(delete("/api/auth/sessions/" + remaining.get(0).get("id").asLong())
                .header("Authorization", "Bearer " + laptop.accessToken())).andExpect(status().isNoContent()).andReturn();
        assertThat(self.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action in ('SESSION_REVOKED','SESSIONS_REVOKED_OTHERS') "
                + "and actor_email = ?", Long.class, email)).isEqualTo(3);
    }

    @Test
    void administratorsSignAUserOutEverywhere() throws Exception {
        String email = account();
        Login one = login(email, true, "Mozilla/5.0 (X11; Linux x86_64) Firefox/131.0");
        Login two = login(email, false, "Mozilla/5.0 (X11; Linux x86_64) Chrome/130.0");
        long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, email);
        long adminId = jdbc.queryForObject("select id from users where email = ?", Long.class, email("admin"));

        assertThat(getJson("rajesh.iyer", "/api/users/" + userId + "/sessions")).hasSize(2);
        mvc.perform(as("priya.raman", post("/api/users/" + userId + "/sessions/revoke"))).andExpect(status().isForbidden());
        mvc.perform(as("rajesh.iyer", post("/api/users/" + adminId + "/sessions/revoke"))).andExpect(status().isForbidden());
        mvc.perform(as("rajesh.iyer", post("/api/users/" + userId + "/sessions/revoke"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(2));
        assertThat(refresh(one.cookie())).isEqualTo(401);
        assertThat(refresh(two.cookie())).isEqualTo(401);
        assertThat(getJson("rajesh.iyer", "/api/users/" + userId + "/sessions")).isEmpty();
        List<String> reasons = jdbc.queryForList("select revoked_reason from auth_sessions s join users u on u.id = s.user_id where u.email = ?",
                String.class, email);
        assertThat(reasons).containsOnly("REVOKED_BY_ADMIN");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'USER_SESSIONS_REVOKED' and entity_id = ?", Long.class,
                String.valueOf(userId))).isEqualTo(1);
    }

    @Test
    void logoutEndsTheSessionAndRefreshReuseRevokesIt() throws Exception {
        String email = account();
        Login a = login(email, true, "Mozilla/5.0 Chrome/130.0");
        mvc.perform(post("/api/auth/logout").cookie(a.cookie()).header("X-Requested-With", "nexus360")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select revoked_reason from auth_sessions s join users u on u.id = s.user_id where u.email = ?",
                String.class, email)).isEqualTo("LOGOUT");
        assertThat(refresh(a.cookie())).isEqualTo(401);
    }
}

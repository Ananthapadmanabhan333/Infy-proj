package com.nexus360.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.security.AuthController;
import com.nexus360.security.Hashing;
import com.nexus360.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** Release 2.1 public account access (docs/PUBLIC_ACCESS_SPEC.md §2–§5): requests, invitations, reset links, admin review. */
class AccountAccessIT extends IntegrationTest {

    private static final String INITIAL_PASSWORD = "Initial-Pass-2026";
    private static final String NEW_PASSWORD = "Fresh-Start-2026";
    private static final String REQUESTS = "/api/admin/account-requests";

    @Autowired
    JdbcTemplate jdbc;

    // ------------------------------------------------------------------ helpers

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private MvcResult requestAccess(String email, String fullName) throws Exception {
        return mvc.perform(jsonBody(post("/api/auth/access-requests"), Map.of("fullName", fullName, "email", email,
                "department", "Finance", "jobTitle", "Analyst", "reason", "I need to raise payroll incidents for my team.")))
                .andReturn();
    }

    private MvcResult forgotPassword(String email) throws Exception {
        return mvc.perform(jsonBody(post("/api/auth/forgot-password"), Map.of("email", email))).andReturn();
    }

    private long requestId(String email, String type) {
        return jdbc.queryForObject("select id from account_requests where lower(email) = lower(?) and type = ? order by id desc limit 1",
                Long.class, email, type);
    }

    private long requestCount(String email, String type) {
        return jdbc.queryForObject("select count(*) from account_requests where lower(email) = lower(?) and type = ?", Long.class, email, type);
    }

    private String requestStatus(long id) {
        return jdbc.queryForObject("select status from account_requests where id = ?", String.class, id);
    }

    private long departmentId(String code) {
        return jdbc.queryForObject("select id from departments where code = ?", Long.class, code);
    }

    private long notificationsFor(String user, String type, String text) {
        return jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id "
                + "where u.email = ? and n.type = ? and position(? in n.message) > 0", Long.class, email(user), type, text);
    }

    private JsonNode approve(String admin, long id, Map<String, Object> body) throws Exception {
        return body(mvc.perform(jsonBody(as(admin, post(REQUESTS + "/" + id + "/approve")), body))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode invite(String admin, String email, String role) throws Exception {
        return body(mvc.perform(jsonBody(as(admin, post("/api/admin/invitations")),
                        Map.of("email", email, "fullName", "Invited Person", "role", role, "departmentId", departmentId("IT-SD"))))
                .andExpect(status().isOk()).andReturn());
    }

    private static String tokenOf(JsonNode linkResponse) {
        String link = linkResponse.get("link").asText();
        return link.substring(link.indexOf('=') + 1);
    }

    /** A fresh active account created by the system administrator; returns its e-mail address. */
    private String createUser(String role) throws Exception {
        String email = uniqueEmail("member");
        mvc.perform(jsonBody(as("admin", post("/api/users")), Map.of("email", email, "fullName", "Test Member", "role", role,
                "departmentId", departmentId("FIN"), "password", INITIAL_PASSWORD))).andExpect(status().isCreated());
        return email;
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(jsonBody(post("/api/auth/login"), Map.of("email", email, "password", password))).andReturn();
    }

    private long rowsContaining(String table, String text) {
        return jdbc.queryForObject("select count(*) from " + table + " t where position(? in t::text) > 0", Long.class, text);
    }

    private static void assertExpiresIn(JsonNode response, Duration validity) {
        Instant expiresAt = Instant.parse(response.get("expiresAt").asText());
        Instant expected = Instant.now().plus(validity);
        assertThat(expiresAt).isBetween(expected.minus(Duration.ofMinutes(2)), expected.plus(Duration.ofMinutes(1)));
    }

    // ------------------------------------------------------------------ access requests and invitations

    @Test
    void accessRequestIsApprovedIntoAnInvitationThatRegistersAWorkingAccount() throws Exception {
        String email = uniqueEmail("applicant");
        MvcResult submitted = requestAccess(email, "Asha Kumar");
        assertThat(submitted.getResponse().getStatus()).isEqualTo(202);
        assertThat(body(submitted).get("message").asText()).isEqualTo(PublicAccessService.ACCESS_REQUEST_RECEIVED);
        long id = requestId(email, "ACCESS");

        // User administrators see it, counted and notified.
        JsonNode page = getJson("rajesh.iyer", REQUESTS + "?type=ACCESS&status=PENDING&size=100");
        JsonNode row = null;
        for (JsonNode r : page.get("content")) {
            if (r.get("id").asLong() == id) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("type").asText()).isEqualTo("ACCESS");
        assertThat(row.get("fullName").asText()).isEqualTo("Asha Kumar");
        assertThat(row.get("email").asText()).isEqualTo(email);
        assertThat(row.get("department").asText()).isEqualTo("Finance");
        assertThat(row.get("jobTitle").asText()).isEqualTo("Analyst");
        assertThat(row.get("reason").asText()).startsWith("I need to raise payroll incidents");
        assertThat(row.get("status").asText()).isEqualTo("PENDING");
        assertThat(row.get("createdAt").isNull()).isFalse();
        assertThat(row.get("decidedByName").isNull()).isTrue();
        assertThat(row.get("userId").isNull()).isTrue();
        assertThat(getJson("rajesh.iyer", REQUESTS + "/counts").get("pendingAccess").asLong()).isPositive();
        assertThat(notificationsFor("admin", "ACCESS_REQUEST", email)).isEqualTo(1);
        assertThat(notificationsFor("rajesh.iyer", "ACCESS_REQUEST", email)).isEqualTo(1);
        assertThat(notificationsFor("priya.raman", "ACCESS_REQUEST", email)).isZero();

        JsonNode link = approve("admin", id, Map.of("role", "EMPLOYEE", "departmentId", departmentId("FIN"), "jobTitle", "Payroll Analyst"));
        assertThat(link.get("link").asText()).startsWith("/register?invite=");
        assertExpiresIn(link, Duration.ofDays(7));
        assertThat(requestStatus(id)).isEqualTo("APPROVED");
        JsonNode decided = getJson("admin", REQUESTS + "?type=ACCESS&status=APPROVED&size=100").get("content");
        assertThat(decided.findValuesAsText("decidedByName")).contains("Aarav Mehta");

        String token = tokenOf(link);
        String organisation = jdbc.queryForObject("select setting_value from system_settings where setting_key = 'org.name'", String.class);
        mvc.perform(get("/api/auth/invitations/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.fullName").value("Asha Kumar"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.departmentName").value("Finance"))
                .andExpect(jsonPath("$.organisationName").value(organisation))
                .andExpect(jsonPath("$.expiresAt").value(link.get("expiresAt").asText()));

        mvc.perform(jsonBody(post("/api/auth/register"), Map.of("token", token, "fullName", "Asha K. Kumar", "password", NEW_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));
        mvc.perform(jsonBody(post("/api/auth/login"), Map.of("email", email, "password", NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.fullName").value("Asha K. Kumar"))
                .andExpect(jsonPath("$.user.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.user.departmentName").value("Finance"))
                .andExpect(jsonPath("$.user.jobTitle").value("Payroll Analyst"))
                .andExpect(jsonPath("$.user.mustChangePassword").value(false));

        long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, email);
        assertThat(requestStatus(id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select user_id from account_requests where id = ?", Long.class, id)).isEqualTo(userId);

        // Single use.
        mvc.perform(get("/api/auth/invitations/" + token)).andExpect(status().isNotFound());
        mvc.perform(jsonBody(post("/api/auth/register"), Map.of("token", token, "fullName", "Someone Else", "password", NEW_PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicatePendingRequestsAndExistingAccountsGetTheSameAnswerWithoutANewRow() throws Exception {
        String email = uniqueEmail("duplicate");
        long existingBefore = requestCount(email("amit.shah"), "ACCESS");

        MvcResult first = requestAccess(email, "First Attempt");
        MvcResult duplicate = requestAccess(email.toUpperCase(Locale.ROOT), "Second Attempt");
        MvcResult existing = requestAccess(email("amit.shah"), "Amit Shah");

        for (MvcResult r : List.of(first, duplicate, existing)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(202);
            assertThat(r.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        }
        assertThat(requestCount(email, "ACCESS")).isEqualTo(1);
        assertThat(requestCount(email("amit.shah"), "ACCESS")).isEqualTo(existingBefore);
        assertThat(notificationsFor("admin", "ACCESS_REQUEST", email)).isEqualTo(1);
    }

    @Test
    void publicRequestBodiesAreValidated() throws Exception {
        mvc.perform(jsonBody(post("/api/auth/access-requests"), Map.of("fullName", "A", "email", "not-an-email", "reason", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'fullName')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'reason')]").exists());
        mvc.perform(jsonBody(post("/api/auth/forgot-password"), Map.of("email", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));
    }

    @Test
    void rejectingRequiresANoteAndDecidesTheRequestOnce() throws Exception {
        String email = uniqueEmail("rejected");
        requestAccess(email, "Rita Rejected");
        long id = requestId(email, "ACCESS");

        mvc.perform(jsonBody(as("admin", post(REQUESTS + "/" + id + "/reject")), Map.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("note"));
        mvc.perform(jsonBody(as("admin", post(REQUESTS + "/" + id + "/reject")), Map.of("note", "   ")))
                .andExpect(status().isBadRequest());
        assertThat(requestStatus(id)).isEqualTo("PENDING");

        mvc.perform(jsonBody(as("rajesh.iyer", post(REQUESTS + "/" + id + "/reject")), Map.of("note", "Not an employee of the organisation")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionNote").value("Not an employee of the organisation"))
                .andExpect(jsonPath("$.decidedByName").value("Rajesh Iyer"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());

        mvc.perform(jsonBody(as("admin", post(REQUESTS + "/" + id + "/reject")), Map.of("note", "again"))).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("admin", post(REQUESTS + "/" + id + "/approve")), Map.of("role", "EMPLOYEE", "departmentId", departmentId("FIN"))))
                .andExpect(status().isConflict());
        mvc.perform(jsonBody(as("admin", post(REQUESTS + "/999999999/reject")), Map.of("note", "x"))).andExpect(status().isNotFound());

        // Once decided, the address may ask again.
        requestAccess(email, "Rita Rejected");
        assertThat(requestCount(email, "ACCESS")).isEqualTo(2);
    }

    @Test
    void itAdministratorsCannotGrantSystemAdministratorButCanGrantOtherRoles() throws Exception {
        long serviceDesk = departmentId("IT-SD");
        String email = uniqueEmail("agent");
        requestAccess(email, "Arun Agent");
        long id = requestId(email, "ACCESS");

        mvc.perform(jsonBody(as("rajesh.iyer", post(REQUESTS + "/" + id + "/approve")), Map.of("role", "SYSTEM_ADMIN", "departmentId", serviceDesk)))
                .andExpect(status().isForbidden());
        assertThat(requestStatus(id)).isEqualTo("PENDING");
        mvc.perform(jsonBody(as("rajesh.iyer", post(REQUESTS + "/" + id + "/approve")), Map.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'role')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'departmentId')]").exists());
        mvc.perform(jsonBody(as("rajesh.iyer", post(REQUESTS + "/" + id + "/approve")), Map.of("role", "SUPPORT_AGENT", "departmentId", 999_999_999)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("departmentId"));
        JsonNode link = approve("rajesh.iyer", id, Map.of("role", "SUPPORT_AGENT", "departmentId", serviceDesk));
        mvc.perform(get("/api/auth/invitations/" + tokenOf(link))).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("SUPPORT_AGENT"));

        // Direct invitations follow the same rule.
        String invitee = uniqueEmail("invitee");
        mvc.perform(jsonBody(as("rajesh.iyer", post("/api/admin/invitations")),
                Map.of("email", invitee, "role", "SYSTEM_ADMIN", "departmentId", serviceDesk))).andExpect(status().isForbidden());
        JsonNode agentInvite = invite("rajesh.iyer", invitee, "SUPPORT_AGENT");
        assertThat(agentInvite.get("link").asText()).startsWith("/register?invite=");
        assertExpiresIn(agentInvite, Duration.ofDays(7));
        JsonNode adminInvite = invite("admin", uniqueEmail("sysadmin"), "SYSTEM_ADMIN");
        mvc.perform(get("/api/auth/invitations/" + tokenOf(adminInvite))).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.departmentName").value("Service Desk"));

        mvc.perform(jsonBody(as("admin", post("/api/admin/invitations")),
                Map.of("email", email("amit.shah"), "role", "EMPLOYEE", "departmentId", serviceDesk))).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("admin", post("/api/admin/invitations")), Map.of("email", "bad", "role", "EMPLOYEE")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approvingAnAccessRequestForAnAddressThatNowHasAnAccountIsAConflict() throws Exception {
        String email = uniqueEmail("raced");
        requestAccess(email, "Rae Raced");
        long id = requestId(email, "ACCESS");
        mvc.perform(jsonBody(as("admin", post("/api/users")), Map.of("email", email, "fullName", "Rae Raced", "role", "EMPLOYEE",
                "password", INITIAL_PASSWORD))).andExpect(status().isCreated());
        mvc.perform(jsonBody(as("admin", post(REQUESTS + "/" + id + "/approve")), Map.of("role", "EMPLOYEE", "departmentId", departmentId("FIN"))))
                .andExpect(status().isConflict());
        assertThat(requestStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void registrationFailsWithConflictWhenTheAddressWasTakenMeanwhile() throws Exception {
        String email = uniqueEmail("taken");
        String token = tokenOf(invite("admin", email, "EMPLOYEE"));
        mvc.perform(jsonBody(as("admin", post("/api/users")), Map.of("email", email, "fullName", "Taken Meanwhile", "role", "EMPLOYEE",
                "password", INITIAL_PASSWORD))).andExpect(status().isCreated());
        mvc.perform(jsonBody(post("/api/auth/register"), Map.of("token", token, "fullName", "Late Comer", "password", NEW_PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    void employeesAgentsManagersAndAuditorsCannotReachTheAdministrationEndpoints() throws Exception {
        Map<String, Object> approval = Map.of("role", "EMPLOYEE", "departmentId", departmentId("FIN"));
        Map<String, Object> invitation = Map.of("email", uniqueEmail("nope"), "role", "EMPLOYEE", "departmentId", departmentId("FIN"));
        for (String user : List.of("amit.shah", "neha.gupta", "priya.raman", "sunita.joshi", "ananya.bose")) {
            mvc.perform(as(user, get(REQUESTS))).andExpect(status().isForbidden());
            mvc.perform(as(user, get(REQUESTS + "/counts"))).andExpect(status().isForbidden());
            mvc.perform(jsonBody(as(user, post(REQUESTS + "/1/approve")), approval)).andExpect(status().isForbidden());
            mvc.perform(jsonBody(as(user, post(REQUESTS + "/1/reject")), Map.of("note", "no"))).andExpect(status().isForbidden());
            mvc.perform(jsonBody(as(user, post("/api/admin/invitations")), invitation)).andExpect(status().isForbidden());
        }
        mvc.perform(get(REQUESTS)).andExpect(status().isUnauthorized());
        mvc.perform(jsonBody(post("/api/admin/invitations"), invitation)).andExpect(status().isUnauthorized());
        assertThat(requestCount((String) invitation.get("email"), "ACCESS")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from account_tokens where email = ?", Long.class, invitation.get("email"))).isZero();
    }

    @Test
    void requestListFiltersByTypeAndStatusNewestFirst() throws Exception {
        String a = uniqueEmail("list-a");
        String b = uniqueEmail("list-b");
        requestAccess(a, "List Alpha");
        requestAccess(b, "List Beta");
        JsonNode content = getJson("admin", REQUESTS + "?type=ACCESS&status=PENDING&size=100").get("content");
        List<String> emails = content.findValuesAsText("email");
        assertThat(emails.indexOf(b)).isLessThan(emails.indexOf(a)).isNotNegative();
        content.forEach(r -> {
            assertThat(r.get("type").asText()).isEqualTo("ACCESS");
            assertThat(r.get("status").asText()).isEqualTo("PENDING");
        });
        mvc.perform(as("admin", get(REQUESTS + "?status=BOGUS"))).andExpect(status().isBadRequest());
        JsonNode resets = getJson("admin", REQUESTS + "?type=PASSWORD_RESET&size=5");
        resets.get("content").forEach(r -> assertThat(r.get("type").asText()).isEqualTo("PASSWORD_RESET"));
        assertThat(resets.get("size").asInt()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ tokens

    @Test
    void linksAreSingleUseExpireAndANewerLinkRevokesTheOlderOne() throws Exception {
        String email = uniqueEmail("expiry");
        String older = tokenOf(invite("admin", email, "EMPLOYEE"));
        String newer = tokenOf(invite("admin", email, "EMPLOYEE"));

        String invalidMessage = body(mvc.perform(get("/api/auth/invitations/" + Hashing.randomToken(32)))
                .andExpect(status().isNotFound()).andReturn()).get("message").asText();
        mvc.perform(get("/api/auth/invitations/" + older)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(invalidMessage));
        assertThat(jdbc.queryForObject("select revoked_at is not null from account_tokens where token_hash = ?", Boolean.class,
                Hashing.sha256Hex(older))).isTrue();
        mvc.perform(get("/api/auth/invitations/" + newer)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/invitations/not-a-token")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(invalidMessage));

        testClock().advance(AccountTokenService.INVITATION_VALIDITY.plusMinutes(1));
        mvc.perform(get("/api/auth/invitations/" + newer)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(invalidMessage));
        mvc.perform(jsonBody(post("/api/auth/register"), Map.of("token", newer, "fullName", "Too Late", "password", NEW_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(invalidMessage));
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Long.class, email)).isZero();
    }

    @Test
    void resetLinksExpireAfterTwoHours() throws Exception {
        String email = createUser("EMPLOYEE");
        forgotPassword(email);
        JsonNode link = approve("admin", requestId(email, "PASSWORD_RESET"), Map.of());
        assertExpiresIn(link, Duration.ofHours(2));
        String token = tokenOf(link);
        mvc.perform(get("/api/auth/reset-password/" + token)).andExpect(status().isOk());

        testClock().advance(AccountTokenService.RESET_VALIDITY.plusMinutes(1));
        mvc.perform(get("/api/auth/reset-password/" + token)).andExpect(status().isNotFound());
        mvc.perform(jsonBody(post("/api/auth/reset-password"), Map.of("token", token, "newPassword", NEW_PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theDatabaseHoldsOnlyTokenHashes() throws Exception {
        String email = uniqueEmail("hashes");
        requestAccess(email, "Hash Holder");
        String token = tokenOf(approve("admin", requestId(email, "ACCESS"), Map.of("role", "EMPLOYEE", "departmentId", departmentId("HR"))));

        assertThat(jdbc.queryForObject("select count(*) from account_tokens where token_hash = ?", Long.class, Hashing.sha256Hex(token)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from account_tokens where token_hash = ?", Long.class, token)).isZero();
        for (String table : List.of("account_tokens", "account_requests", "audit_logs", "notifications")) {
            assertThat(rowsContaining(table, token)).as(table).isZero();
        }
    }

    // ------------------------------------------------------------------ password reset

    @Test
    void forgotPasswordAnswersIdenticallyForUnknownAndKnownAddresses() throws Exception {
        String known = createUser("EMPLOYEE");
        String unknown = uniqueEmail("nobody");
        String inactive = createUser("EMPLOYEE");
        jdbc.update("update users set active = false where email = ?", inactive);

        MvcResult forUnknown = forgotPassword(unknown);
        MvcResult forKnown = forgotPassword(known);
        MvcResult forInactive = forgotPassword(inactive);
        MvcResult again = forgotPassword(known.toUpperCase(Locale.ROOT));
        for (MvcResult r : List.of(forUnknown, forKnown, forInactive, again)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(202);
            assertThat(r.getResponse().getContentAsString()).isEqualTo(forUnknown.getResponse().getContentAsString());
        }
        assertThat(body(forKnown).get("message").asText()).isEqualTo(PublicAccessService.RESET_REQUEST_RECEIVED);

        assertThat(requestCount(unknown, "PASSWORD_RESET")).isZero();
        assertThat(requestCount(inactive, "PASSWORD_RESET")).isZero();
        assertThat(requestCount(known, "PASSWORD_RESET")).isEqualTo(1); // deduplicated while pending
        long id = requestId(known, "PASSWORD_RESET");
        assertThat(requestStatus(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select user_id from account_requests where id = ?", Long.class, id))
                .isEqualTo(jdbc.queryForObject("select id from users where email = ?", Long.class, known));
        assertThat(notificationsFor("admin", "PASSWORD_RESET_REQUEST", known)).isEqualTo(1);
        assertThat(notificationsFor("rajesh.iyer", "PASSWORD_RESET_REQUEST", known)).isEqualTo(1);
        assertThat(notificationsFor("admin", "PASSWORD_RESET_REQUEST", unknown)).isZero();
        assertThat(getJson("admin", REQUESTS + "/counts").get("pendingReset").asLong()).isPositive();
    }

    @Test
    void resetLinkSetsThePasswordRevokesSessionsAndClearsTheLockout() throws Exception {
        String email = createUser("EMPLOYEE");
        Cookie session = login(email, INITIAL_PASSWORD).getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(session).isNotNull();
        jdbc.update("update users set locked_until = now() + interval '1 hour', failed_login_attempts = 3 where email = ?", email);

        forgotPassword(email);
        long id = requestId(email, "PASSWORD_RESET");
        JsonNode link = approve("admin", id, Map.of());
        assertThat(link.get("link").asText()).startsWith("/reset-password?token=");
        assertThat(requestStatus(id)).isEqualTo("APPROVED");
        String token = tokenOf(link);

        mvc.perform(get("/api/auth/reset-password/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.expiresAt").value(link.get("expiresAt").asText()));

        // A policy failure leaves the link usable.
        mvc.perform(jsonBody(post("/api/auth/reset-password"), Map.of("token", token, "newPassword", "lettersonlypassword")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));
        mvc.perform(jsonBody(post("/api/auth/reset-password"), Map.of("token", token, "newPassword", NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        Map<String, Object> user = jdbc.queryForMap("select locked_until, failed_login_attempts, must_change_password from users where email = ?", email);
        assertThat(user.get("locked_until")).isNull();
        assertThat(user.get("failed_login_attempts")).isEqualTo(0);
        assertThat(user.get("must_change_password")).isEqualTo(false);
        mvc.perform(post("/api/auth/refresh").cookie(session).header(AuthController.CSRF_HEADER, AuthController.CSRF_HEADER_VALUE))
                .andExpect(status().isUnauthorized());
        assertThat(login(email, INITIAL_PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(email, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(requestStatus(id)).isEqualTo("COMPLETED");

        mvc.perform(get("/api/auth/reset-password/" + token)).andExpect(status().isNotFound());
        mvc.perform(jsonBody(post("/api/auth/reset-password"), Map.of("token", token, "newPassword", "Another-Pass-2026")))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyASystemAdministratorIssuesResetLinksForSystemAdministrators() throws Exception {
        String email = createUser("SYSTEM_ADMIN");
        forgotPassword(email);
        long id = requestId(email, "PASSWORD_RESET");
        mvc.perform(jsonBody(as("rajesh.iyer", post(REQUESTS + "/" + id + "/approve")), Map.of())).andExpect(status().isForbidden());
        assertThat(requestStatus(id)).isEqualTo("PENDING");
        approve("admin", id, Map.of());
        assertThat(requestStatus(id)).isEqualTo("APPROVED");
    }

    @Test
    void passwordPolicyViolationsAreReportedOnThePasswordField() throws Exception {
        String email = uniqueEmail("policy");
        String token = tokenOf(invite("admin", email, "EMPLOYEE"));
        String localPart = email.substring(0, email.indexOf('@'));
        for (String weak : List.of("lettersonlypassword", "short1", localPart + "-Ab1")) {
            mvc.perform(jsonBody(post("/api/auth/register"), Map.of("token", token, "fullName", "Pat Policy", "password", weak)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
        }
        mvc.perform(get("/api/auth/invitations/" + token)).andExpect(status().isOk()); // still usable
    }

    // ------------------------------------------------------------------ public info, audit, notifications

    @Test
    void publicInfoNeedsNoSessionAndReflectsTheOrganisationSettings() throws Exception {
        String previous = jdbc.queryForObject("select setting_value from system_settings where setting_key = 'org.support_email'", String.class);
        String organisation = jdbc.queryForObject("select setting_value from system_settings where setting_key = 'org.name'", String.class);
        try {
            mvc.perform(jsonBody(as("admin", put("/api/admin/settings/org.support_email")), Map.of("value", "")))
                    .andExpect(status().isOk());
            JsonNode info = body(mvc.perform(get("/api/public/info")).andExpect(status().isOk()).andReturn());
            assertThat(info.get("organisationName").asText()).isEqualTo(organisation);
            assertThat(info.has("supportEmail")).isTrue();
            assertThat(info.get("supportEmail").isNull()).isTrue();
            assertThat(info.get("version").asText()).isEqualTo("2.0.0");
            mvc.perform(jsonBody(as("admin", put("/api/admin/settings/org.support_email")), Map.of("value", "help@example.com")))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/public/info")).andExpect(jsonPath("$.supportEmail").value("help@example.com"));
        } finally {
            mvc.perform(jsonBody(as("admin", put("/api/admin/settings/org.support_email")), Map.of("value", previous)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void everyStepIsAuditedWithoutTokensOrPasswords() throws Exception {
        List<String> secrets = new ArrayList<>(List.of(NEW_PASSWORD, INITIAL_PASSWORD));

        String applicant = uniqueEmail("audited");
        requestAccess(applicant, "Audit Applicant");
        String inviteToken = tokenOf(approve("admin", requestId(applicant, "ACCESS"),
                Map.of("role", "EMPLOYEE", "departmentId", departmentId("HR"))));
        secrets.add(inviteToken);
        mvc.perform(jsonBody(post("/api/auth/register"), Map.of("token", inviteToken, "fullName", "Audit Applicant", "password", NEW_PASSWORD)))
                .andExpect(status().isCreated());

        String rejected = uniqueEmail("audit-rejected");
        requestAccess(rejected, "Audit Rejected");
        mvc.perform(jsonBody(as("admin", post(REQUESTS + "/" + requestId(rejected, "ACCESS") + "/reject")), Map.of("note", "Duplicate")))
                .andExpect(status().isOk());

        String direct = uniqueEmail("audit-direct");
        secrets.add(tokenOf(invite("admin", direct, "AUDITOR")));

        String member = createUser("EMPLOYEE");
        forgotPassword(member);
        String resetToken = tokenOf(approve("admin", requestId(member, "PASSWORD_RESET"), Map.of()));
        secrets.add(resetToken);
        mvc.perform(jsonBody(post("/api/auth/reset-password"), Map.of("token", resetToken, "newPassword", "Reset-Done-2026")))
                .andExpect(status().isNoContent());
        secrets.add("Reset-Done-2026");

        Map<String, String> expected = new HashMap<>();
        expected.put("ACCESS_REQUESTED", applicant);
        expected.put("USER_REGISTERED", applicant);
        expected.put("ACCESS_REQUEST_REJECTED", rejected);
        expected.put("INVITATION_CREATED", direct);
        expected.put("PASSWORD_RESET_REQUESTED", member);
        expected.put("PASSWORD_RESET_LINK_CREATED", member);
        expected.put("PASSWORD_RESET_COMPLETED", member);
        expected.forEach((action, email) -> assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = ? and (actor_email = ? or details ->> 'email' = ?)", Long.class, action, email, email))
                .as(action).isPositive());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ACCESS_REQUEST_APPROVED' and details ->> 'email' = ?",
                Long.class, applicant)).isEqualTo(1);
        for (String secret : secrets) {
            assertThat(rowsContaining("audit_logs", secret)).as("audit rows containing a secret").isZero();
        }
    }

    @Test
    void notificationPreferencesIncludeTheAccountRequestTypesAndAreHonoured() throws Exception {
        JsonNode prefs = getJson("rajesh.iyer", "/api/notifications/preferences");
        assertThat(prefs.findValuesAsText("type")).contains("ACCESS_REQUEST", "PASSWORD_RESET_REQUEST");
        try {
            mvc.perform(jsonBody(as("rajesh.iyer", put("/api/notifications/preferences")), Map.of("ACCESS_REQUEST", false)))
                    .andExpect(status().isOk());
            String email = uniqueEmail("muted");
            requestAccess(email, "Muted Notice");
            assertThat(notificationsFor("rajesh.iyer", "ACCESS_REQUEST", email)).isZero();
            assertThat(notificationsFor("admin", "ACCESS_REQUEST", email)).isEqualTo(1);
        } finally {
            mvc.perform(jsonBody(as("rajesh.iyer", put("/api/notifications/preferences")), Map.of("ACCESS_REQUEST", true)))
                    .andExpect(status().isOk());
        }
    }
}

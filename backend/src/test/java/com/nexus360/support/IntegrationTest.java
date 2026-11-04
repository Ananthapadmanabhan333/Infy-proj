package com.nexus360.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Full application context on a real PostgreSQL 17 (embedded, Flyway-migrated) with the demo organisation seeded,
 * the real security filter chain and a controllable clock. One context is shared by all integration tests, so
 * tests create their own records and assert on those (never on global counts).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.ClockConfig.class)
public abstract class IntegrationTest {

    public static final String DEMO_PASSWORD = "Integration-Demo-2026";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("app.security.jwt-secret", () -> "integration-tests-only-secret-0123456789abcdef");
        registry.add("app.security.allowed-origins", () -> "http://localhost:5173");
        registry.add("app.security.cookie-secure", () -> "false");
        registry.add("app.security.cookie-same-site", () -> "Lax");
        registry.add("app.demo.seed", () -> "true");
        registry.add("app.demo.password", () -> DEMO_PASSWORD);
        registry.add("app.rate-limit.enabled", () -> "false");
        registry.add("app.sla.scheduler-enabled", () -> "false");
        registry.add("app.integrations.allow-private-targets", () -> "true");
        registry.add("app.workflow.retry-poll-interval-ms", () -> "600000");
        // Scheduled workflows are driven explicitly by the tests (WorkflowEngine#processScheduled).
        registry.add("app.workflow.scheduler-enabled", () -> "false");
        // The shared test database accumulates active incidents; evaluate all of them in one scheduled run.
        registry.add("app.workflow.scheduled-max-incidents", () -> "1000");
        // Release 3: the e-mail outbox and report schedulers are driven explicitly by the tests; no SMTP (MAIL_HOST) by default.
        registry.add("app.mail.outbox-enabled", () -> "false");
        registry.add("app.reports.scheduler-enabled", () -> "false");
        // Release 3 BE-2: approval deadline escalation is driven explicitly by the tests (ApprovalDeadlineScheduler#runEscalations).
        registry.add("app.approvals.scheduler-enabled", () -> "false");
        // Release 3 BE-3: alert escalation is driven explicitly by the tests (AlertEscalationService#runEscalations).
        registry.add("app.alerts.escalation-enabled", () -> "false");
        // Release 4 BE-5: simulation is allowed; its scheduler and the observability jobs are driven explicitly by the tests.
        registry.add("app.simulation.allowed", () -> "true");
        registry.add("app.simulation.scheduler-enabled", () -> "false");
        registry.add("app.observability.jobs-enabled", () -> "false");
        registry.add("app.observability.correlation-async", () -> "false");
        registry.add("app.predictions.jobs-enabled", () -> "false");
    }

    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected Clock clock;

    @AfterEach
    void resetClock() {
        ((MutableClock) clock).reset();
    }

    protected MutableClock testClock() {
        return (MutableClock) clock;
    }

    /** Access token for a demo user, e.g. {@code token("priya.raman")}. */
    protected String token(String user) {
        return TOKENS.computeIfAbsent(user, u -> {
            try {
                MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email(u), "password", DEMO_PASSWORD)))).andReturn();
                if (result.getResponse().getStatus() != 200) {
                    throw new IllegalStateException("Login failed for " + u + ": " + result.getResponse().getContentAsString());
                }
                return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    protected static String email(String user) {
        return user + "@demo.nexus360.io";
    }

    protected MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token(user));
    }

    protected MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode getJson(String user, String path) throws Exception {
        return body(mvc.perform(as(user, get(path))).andReturn());
    }

    /** Text that will not be mistaken for another incident by duplicate detection. */
    protected static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Creates an incident as {@code user} and returns the CreateIncidentResult JSON. */
    protected JsonNode createIncident(String user, Map<String, Object> request) throws Exception {
        MvcResult result = mvc.perform(jsonBody(as(user, post("/api/incidents")), request)).andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Create failed: " + result.getResponse().getContentAsString());
        }
        return body(result);
    }

    protected static void await(java.util.function.BooleanSupplier condition, String description) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }
}

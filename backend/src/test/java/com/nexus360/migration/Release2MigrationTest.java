package com.nexus360.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.support.TestDatabase;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V3 against Release 1 data: a scratch database is migrated to V2, filled with Release 1 roles, statuses, approvals,
 * catalogue rows and workflow JSON, then migrated to the latest version.
 */
class Release2MigrationTest {

    private static final String DB = "r2_migration_test";
    private static EmbeddedPostgres pg;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateReleaseOneData() throws Exception {
        pg = TestDatabase.get();
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("drop database if exists " + DB);
            s.execute("create database " + DB);
        }
        DataSource ds = pg.getDatabase("postgres", DB);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("2").load().migrate();
        jdbc = new JdbcTemplate(ds);

        jdbc.update("insert into departments (code, name) values ('IT-SD', 'Service Desk')");
        Long dept = jdbc.queryForObject("select id from departments where code = 'IT-SD'", Long.class);
        jdbc.update("insert into users (email, full_name, password_hash, role, department_id, is_demo) values "
                + "('admin@demo.nexus360.io', 'Aarav Mehta', '$2a$10$hashhashhashhashhashhu', 'ADMIN', ?, true), "
                + "('sunita.joshi@demo.nexus360.io', 'Sunita Joshi', 'x', 'MANAGER', ?, true), "
                + "('m@x.io', 'Manager', 'x', 'MANAGER', null, false), "
                + "('a@x.io', 'Agent', 'x', 'AGENT', null, false), "
                + "('e@x.io', 'Employee', 'x', 'EMPLOYEE', null, false)", dept, dept);
        jdbc.update("insert into incidents (number, title, category, impact, urgency, priority, status, updated_at) values "
                + "('INC-1', 'open one', 'OTHER', 'LOW', 'LOW', 'LOW', 'OPEN', now()), "
                + "('INC-2', 'on hold one', 'OTHER', 'LOW', 'LOW', 'LOW', 'ON_HOLD', now()), "
                + "('INC-3', 'cancelled one', 'OTHER', 'LOW', 'LOW', 'LOW', 'CANCELLED', now() - interval '1 day')");
        Long inc1 = jdbc.queryForObject("select id from incidents where number = 'INC-1'", Long.class);
        jdbc.update("insert into incident_history (incident_id, actor_name, event_type, message) values (?, 'Agent', 'ESCALATED', "
                + "'Escalated to level 2: customer impact')", inc1);
        jdbc.update("insert into service_categories (code, name, is_demo) values ('IT-SUPPORT', 'IT Support', true), "
                + "('OTHER', 'Other Requests', true), ('ACCESS', 'Access Management', true)");
        jdbc.update("insert into catalog_items (category_id, name, fulfillment_department_id, requires_approval, approver_role, is_demo) "
                + "values ((select id from service_categories where code = 'ACCESS'), 'VPN access', ?, true, 'MANAGER', true), "
                + "((select id from service_categories where code = 'OTHER'), 'General IT help', ?, false, null, true)", dept, dept);
        jdbc.update("insert into service_requests (number, catalog_item_id, requester_id, title, status) values "
                + "('REQ-1', (select id from catalog_items where name = 'VPN access'), (select id from users where email = 'e@x.io'), 'VPN', 'PENDING_APPROVAL')");
        jdbc.update("insert into approvals (approval_type, reference_id, title, approver_role, status, decided_at) values "
                + "('RUNBOOK_EXECUTION', 1, 'pending runbook', 'MANAGER', 'PENDING', null), "
                + "('AI_ACTION', 2, 'decided ai', 'MANAGER', 'APPROVED', now()), "
                + "('SERVICE_REQUEST', 3, 'pending request', 'MANAGER', 'PENDING', null), "
                + "('SERVICE_REQUEST', 4, 'admin request', 'ADMIN', 'PENDING', null)");
        jdbc.update("insert into workflows (name, trigger_event) values ('legacy', 'INCIDENT_STATUS_CHANGED')");
        jdbc.update("insert into workflow_versions (workflow_id, version_number, definition) values ((select id from workflows), 1, ?::jsonb)",
                "{\"conditions\":{\"match\":\"ALL\",\"rules\":[{\"field\":\"status\",\"operator\":\"IN\",\"value\":\"OPEN, ON_HOLD,RESOLVED\"},"
                        + "{\"field\":\"priority\",\"operator\":\"EQUALS\",\"value\":\"OPEN\"}]},"
                        + "\"actions\":[{\"type\":\"NOTIFY_ROLE\",\"params\":{\"role\":\"MANAGER\"}},{\"type\":\"NOTIFY_ROLE\",\"params\":{\"role\":\"ADMIN\"}},"
                        + "{\"type\":\"ADD_COMMENT\",\"params\":{\"message\":\"ADMIN check\"}}]}");
        jdbc.update("insert into knowledge_articles (number, title, summary, content, article_type, category, status, helpful_count, not_helpful_count) "
                + "values ('KB-1', 'Legacy article', 'Legacy summary text', 'Legacy content body', 'GUIDE', 'OTHER', 'DRAFT', 7, 2)");
        jdbc.update("insert into runbooks (title, category, steps) values ('Legacy runbook', 'NETWORK', '[{\"order\":1,\"title\":\"a\",\"instruction\":\"b\"}]'::jsonb)");

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("select pg_terminate_backend(pid) from pg_stat_activity where datname = '" + DB + "' and pid <> pg_backend_pid()");
            s.execute("drop database if exists " + DB);
        }
    }

    private String role(String email) {
        return jdbc.queryForObject("select role from users where email = ?", String.class, email);
    }

    @Test
    void rolesAreRenamedAndTheDemoOrganisationGetsItsReleaseTwoRoles() {
        assertThat(role("admin@demo.nexus360.io")).isEqualTo("SYSTEM_ADMIN");
        assertThat(role("m@x.io")).isEqualTo("IT_MANAGER");
        assertThat(role("a@x.io")).isEqualTo("SUPPORT_AGENT");
        assertThat(role("e@x.io")).isEqualTo("EMPLOYEE");
        assertThat(role("sunita.joshi@demo.nexus360.io")).isEqualTo("INCIDENT_MANAGER");
        Map<String, Object> rajesh = jdbc.queryForMap("select role, password_hash, is_demo, job_title from users where email = 'rajesh.iyer@demo.nexus360.io'");
        assertThat(rajesh).containsEntry("role", "IT_ADMIN").containsEntry("is_demo", true).containsEntry("job_title", "IT Platform Administrator");
        assertThat(rajesh.get("password_hash")).isEqualTo("$2a$10$hashhashhashhashhashhu");
    }

    @Test
    void incidentStatusesAreMappedAndCancellationsBecomeClosedWithAResolution() {
        assertThat(jdbc.queryForObject("select status from incidents where number = 'INC-1'", String.class)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("select status from incidents where number = 'INC-2'", String.class)).isEqualTo("PENDING");
        Map<String, Object> cancelled = jdbc.queryForMap("select status, resolution_code, resolved_at, closed_at from incidents where number = 'INC-3'");
        assertThat(cancelled).containsEntry("status", "CLOSED").containsEntry("resolution_code", "CANCELLED");
        assertThat(cancelled.get("resolved_at")).isNotNull();
        assertThat(cancelled.get("closed_at")).isNotNull();
        assertThat(jdbc.queryForObject("select level from incident_escalations", Integer.class)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update incidents set status = 'OPEN' where number = 'INC-1'"))
                .hasMessageContaining("ck_incident_status");
    }

    @Test
    void approvalsAndCatalogueFollowTheReleaseTwoRouting() {
        List<Map<String, Object>> rows = jdbc.queryForList("select title, approver_role from approvals order by id");
        assertThat(rows).extracting(r -> r.get("approver_role"))
                .containsExactly("INCIDENT_MANAGER", "IT_MANAGER", "IT_MANAGER", "SYSTEM_ADMIN");
        Map<String, Object> vpn = jdbc.queryForMap("select i.approver_role, c.code from catalog_items i join service_categories c on c.id = i.category_id "
                + "where i.name = 'VPN access'");
        assertThat(vpn).containsEntry("approver_role", "IT_MANAGER").containsEntry("code", "NETWORK");
        assertThat(jdbc.queryForObject("select c.code from catalog_items i join service_categories c on c.id = i.category_id "
                + "where i.name = 'General IT help'", String.class)).isEqualTo("GENERAL");
        assertThat(jdbc.queryForList("select code from service_categories order by sort_order", String.class))
                .containsExactly("HARDWARE", "SOFTWARE", "NETWORK", "ACCESS", "ACCOUNT", "FACILITIES", "GENERAL");
        assertThat(jdbc.queryForObject("select priority from service_requests", String.class)).isEqualTo("MEDIUM");
        assertThat(jdbc.queryForObject("select action from service_request_history", String.class)).isEqualTo("CREATED");
    }

    @Test
    void workflowDefinitionsKnowledgeAndRunbooksAreUpgraded() throws Exception {
        JsonNode definition = new ObjectMapper().readTree(jdbc.queryForObject("select definition::text from workflow_versions", String.class));
        assertThat(definition.at("/conditions/rules/0/value").asText()).isEqualTo("NEW,PENDING,RESOLVED");
        assertThat(definition.at("/conditions/rules/1/value").asText()).isEqualTo("OPEN"); // only "status" rules are rewritten
        assertThat(definition.at("/actions/0/params/role").asText()).isEqualTo("IT_MANAGER");
        assertThat(definition.at("/actions/1/params/role").asText()).isEqualTo("SYSTEM_ADMIN");
        assertThat(definition.at("/actions/2/params/message").asText()).isEqualTo("ADMIN check");

        Map<String, Object> kb = jdbc.queryForMap("select legacy_helpful_count, legacy_not_helpful_count, rating_count, current_version "
                + "from knowledge_articles");
        assertThat(kb).containsEntry("legacy_helpful_count", 7).containsEntry("legacy_not_helpful_count", 2).containsEntry("rating_count", 0);
        assertThat(jdbc.queryForObject("select count(*) from kb_article_versions", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select category from runbook_categories", String.class)).isEqualTo("NETWORK");
        assertThat(jdbc.queryForObject("select count(*) from incident_categories", Long.class)).isEqualTo(9);
        // V3 inserted "NEXUS 360", V5 renamed it to "SA 360" and V6 to "SA" (each only while unchanged).
        assertThat(jdbc.queryForObject("select setting_value from system_settings where setting_key = 'org.name'", String.class)).isEqualTo("SA");
    }
}

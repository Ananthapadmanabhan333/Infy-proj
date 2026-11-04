package com.nexus360.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus360.support.TestDatabase;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V7 against Release 3 foundation data: catalogue items keep their fields as version 1 of their own form definition (and
 * requests point at it), existing approvals become one-stage approvals with one role task (status, decider and comment
 * preserved), the change approval flows exist and the new constraints hold.
 */
class Release7MigrationTest {

    private static final EmbeddedPostgres PG = TestDatabase.get();
    private static final String DB = "r7_migration";

    @AfterAll
    static void dropDatabase() throws Exception {
        drop();
    }

    private static void drop() throws Exception {
        try (Connection c = PG.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("select pg_terminate_backend(pid) from pg_stat_activity where datname = '" + DB + "' and pid <> pg_backend_pid()");
            s.execute("drop database if exists " + DB);
        }
    }

    @Test
    void catalogueFormsAndApprovalsAreMigratedWithoutDataLoss() throws Exception {
        drop();
        try (Connection c = PG.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("create database " + DB);
        }
        DataSource ds = PG.getDatabase("postgres", DB);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("6").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into departments (code, name) values ('IT-SD', 'Service Desk')");
        long dept = jdbc.queryForObject("select id from departments where code = 'IT-SD'", Long.class);
        jdbc.update("insert into users (email, full_name, password_hash, role, department_id) values ('e@x.io', 'Emp', 'x', 'EMPLOYEE', ?), "
                + "('m@x.io', 'Mgr', 'x', 'IT_MANAGER', ?)", dept, dept);
        long employee = jdbc.queryForObject("select id from users where email = 'e@x.io'", Long.class);
        long manager = jdbc.queryForObject("select id from users where email = 'm@x.io'", Long.class);
        long category = jdbc.queryForObject("select id from service_categories where code = 'NETWORK'", Long.class);
        String fields = "[{\"key\":\"startDate\",\"label\":\"Needed from\",\"type\":\"date\",\"required\":true,\"options\":null,\"placeholder\":null},"
                + "{\"key\":\"why\",\"label\":\"Why\",\"type\":\"select\",\"required\":false,\"options\":[\"Travel\",\"Home\"],\"placeholder\":null}]";
        jdbc.update("insert into catalog_items (category_id, name, fulfillment_department_id, requires_approval, approver_role, form_fields) "
                + "values (?, 'VPN access', ?, true, 'IT_MANAGER', ?::jsonb)", category, dept, fields);
        long item = jdbc.queryForObject("select id from catalog_items where name = 'VPN access'", Long.class);
        jdbc.update("insert into service_requests (number, catalog_item_id, requester_id, title, status, form_data) "
                + "values ('REQ-1', ?, ?, 'VPN please', 'PENDING_APPROVAL', '{\"startDate\":\"2026-12-01\"}'::jsonb)", item, employee);
        long request = jdbc.queryForObject("select id from service_requests where number = 'REQ-1'", Long.class);
        jdbc.update("insert into approvals (approval_type, reference_id, reference_number, title, approver_role, requested_by_id, status) "
                + "values ('SERVICE_REQUEST', ?, 'REQ-1', 'VPN access for Emp', 'IT_MANAGER', ?, 'PENDING')", request, employee);
        jdbc.update("insert into approvals (approval_type, reference_id, title, approver_role, status, decided_by_id, decided_at, decision_comment) "
                + "values ('AI_ACTION', 1, 'Old AI action', 'INCIDENT_MANAGER', 'REJECTED', ?, now(), 'Not needed')", manager);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        Map<String, Object> form = jdbc.queryForMap("select f.id, f.code, f.current_version, v.fields::text as fields from form_definitions f "
                + "join form_definition_versions v on v.form_id = f.id where f.code = ?", "ITEM-" + item);
        assertThat(form.get("current_version")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select ?::jsonb = ?::jsonb", Boolean.class, form.get("fields"), fields)).isTrue();
        assertThat(jdbc.queryForObject("select form_definition_id from catalog_items where id = ?", Long.class, item)).isEqualTo(form.get("id"));
        assertThat(jdbc.queryForMap("select form_definition_id, form_version from service_requests where id = ?", request))
                .containsEntry("form_definition_id", form.get("id")).containsEntry("form_version", 1);
        assertThat(jdbc.queryForObject("select form_fields::text from catalog_items where id = ?", String.class, item)).isNotBlank(); // kept

        List<Map<String, Object>> tasks = jdbc.queryForList("select a.title, s.status as stage, t.approver_kind, t.approver_role, t.status, "
                + "t.decided_by_id, t.comment, a.current_stage from approvals a join approval_stages s on s.approval_id = a.id "
                + "join approval_tasks t on t.stage_id = s.id order by a.id");
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0)).containsEntry("stage", "ACTIVE").containsEntry("approver_kind", "ROLE").containsEntry("approver_role", "IT_MANAGER")
                .containsEntry("status", "PENDING").containsEntry("current_stage", 1);
        assertThat(tasks.get(1)).containsEntry("stage", "REJECTED").containsEntry("status", "REJECTED").containsEntry("decided_by_id", manager)
                .containsEntry("comment", "Not needed");
        assertThat(jdbc.queryForList("select event_type from approval_events order by id", String.class)).contains("REQUESTED", "TASK_REJECTED");
        assertThat(jdbc.queryForList("select code from approval_flows where system_flow order by code", String.class))
                .containsExactly("CHANGE_EMERGENCY", "CHANGE_NORMAL");
        assertThat(jdbc.queryForObject("select setting_value from system_settings where setting_key = 'change.correlation.hours'", String.class))
                .isEqualTo("24");
        assertThatThrownBy(() -> jdbc.update("insert into changes (number, title, change_type, status) values ('CHG-1', 'x', 'NORMAL', 'DONE')"))
                .hasMessageContaining("ck_change_status");
        assertThatThrownBy(() -> jdbc.update("insert into approvals (approval_type, reference_id, title, status) values ('BOGUS', 1, 'x', 'PENDING')"))
                .hasMessageContaining("ck_approval_type");
        jdbc.update("insert into approvals (approval_type, reference_id, title, status) values ('CHANGE', 1, 'Flow approval', 'PENDING')");
    }
}

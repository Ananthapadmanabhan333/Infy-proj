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
 * V8 against Release 3 / BE-2 data: every business service gets a BUSINESS_SERVICE CI, service dependencies become DEPENDS_ON
 * relationships (origin SERVICE_MAP), problems and changes are linked to CIs whose names match their affected-CI text exactly
 * (ignoring case), and the new constraints hold.
 */
class Release8MigrationTest {

    private static final EmbeddedPostgres PG = TestDatabase.get();
    private static final String DB = "r8_migration";

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
    void servicesBecomeCisAndAffectedCiNamesAreLinked() throws Exception {
        drop();
        try (Connection c = PG.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("create database " + DB);
        }
        DataSource ds = PG.getDatabase("postgres", DB);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("7").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into departments (code, name) values ('IT-APPS', 'Applications')");
        long dept = jdbc.queryForObject("select id from departments where code = 'IT-APPS'", Long.class);
        jdbc.update("insert into business_services (code, name, department_id, tier, is_demo) values ('PAY', 'Payroll', ?, 1, true), "
                + "('DB', 'Database Platform', ?, 2, false), ('FAC', 'Facilities', ?, 3, false)", dept, dept, dept);
        long pay = jdbc.queryForObject("select id from business_services where code = 'PAY'", Long.class);
        long db = jdbc.queryForObject("select id from business_services where code = 'DB'", Long.class);
        jdbc.update("insert into service_dependencies (service_id, depends_on_id) values (?, ?)", pay, db);
        jdbc.update("insert into problems (number, title, affected_cis) values ('PRB-1', 'Payroll 503', '[\"Database Platform\", \"payroll-app-01\"]'::jsonb)");
        jdbc.update("insert into changes (number, title, change_type, affected_cis) values ('CHG-1', 'Payroll patch', 'NORMAL', '[\" PAYROLL \"]'::jsonb)");

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        List<Map<String, Object>> cis = jdbc.queryForList("select name, ci_type, criticality, is_demo, attributes ->> 'serviceCode' as code "
                + "from configuration_items order by name");
        assertThat(cis).hasSize(3);
        assertThat(cis).extracting(m -> m.get("ci_type")).containsOnly("BUSINESS_SERVICE");
        assertThat(cis.get(2)).containsEntry("name", "Payroll").containsEntry("criticality", "CRITICAL").containsEntry("is_demo", true)
                .containsEntry("code", "PAY");
        assertThat(cis.get(1)).containsEntry("name", "Facilities").containsEntry("criticality", "MEDIUM");
        Map<String, Object> rel = jdbc.queryForMap("select s.name as source, t.name as target, r.rel_type, r.origin from ci_relationships r "
                + "join configuration_items s on s.id = r.source_id join configuration_items t on t.id = r.target_id");
        assertThat(rel).containsEntry("source", "Payroll").containsEntry("target", "Database Platform").containsEntry("rel_type", "DEPENDS_ON")
                .containsEntry("origin", "SERVICE_MAP");
        assertThat(jdbc.queryForList("select c.name from problem_cis l join configuration_items c on c.id = l.ci_id", String.class))
                .containsExactly("Database Platform");
        assertThat(jdbc.queryForList("select c.name from change_cis l join configuration_items c on c.id = l.ci_id", String.class)).containsExactly("Payroll");
        assertThat(jdbc.queryForObject("select count(*) from ci_history where action = 'CREATED'", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select setting_value from system_settings where setting_key = 'alert.escalation.ack_minutes'", String.class))
                .isEqualTo("15");

        // Constraints: case-insensitive unique names, licence seats only for licences, one active alert per fingerprint.
        assertThatThrownBy(() -> jdbc.update("insert into configuration_items (name, ci_type) values ('PAYROLL', 'APPLICATION')")).hasMessageContaining("ux_ci_name");
        assertThatThrownBy(() -> jdbc.update("insert into configuration_items (name, ci_type, service_id) values ('x', 'SERVER', ?)", db))
                .hasMessageContaining("ck_ci_service");
        assertThatThrownBy(() -> jdbc.update("insert into assets (asset_tag, name, asset_type) values ('A-1', 'Office', 'SOFTWARE_LICENSE')"))
                .hasMessageContaining("ck_asset_license");
        jdbc.update("insert into alerts (number, source_kind, scope_key, fingerprint, title, severity, first_seen_at, last_seen_at) "
                + "values ('ALR-1', 'MANUAL', 'MANUAL', 'fp', 'Disk full', 'MAJOR', now(), now())");
        assertThatThrownBy(() -> jdbc.update("insert into alerts (number, source_kind, scope_key, fingerprint, title, severity, first_seen_at, last_seen_at) "
                + "values ('ALR-2', 'MANUAL', 'MANUAL', 'fp', 'Disk full', 'MAJOR', now(), now())")).hasMessageContaining("ux_alert_active_fingerprint");
        jdbc.update("update alerts set status = 'RESOLVED', resolved_at = now() where number = 'ALR-1'");
        jdbc.update("insert into alerts (number, source_kind, scope_key, fingerprint, title, severity, first_seen_at, last_seen_at) "
                + "values ('ALR-2', 'MANUAL', 'MANUAL', 'fp', 'Disk full', 'MAJOR', now(), now())"); // a resolved alert does not block a new one
    }
}

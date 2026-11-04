package com.nexus360.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus360.support.TestDatabase;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V6 against Release 2.1 data: the default organisation name is renamed only when unchanged, existing refresh-token
 * families become sessions, the new roles are accepted and the status page settings row exists.
 */
class Release3MigrationTest {

    private static final EmbeddedPostgres PG = TestDatabase.get();

    @AfterAll
    static void dropDatabases() throws Exception {
        for (String db : new String[]{"r3_migration_default", "r3_migration_custom"}) {
            drop(db);
        }
    }

    private static JdbcTemplate migrateToV5(String db) throws Exception {
        drop(db);
        try (Connection c = PG.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        DataSource ds = PG.getDatabase("postgres", db);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("5").load().migrate();
        return new JdbcTemplate(ds);
    }

    private static void migrate(JdbcTemplate jdbc) {
        Flyway.configure().dataSource(jdbc.getDataSource()).locations("classpath:db/migration").load().migrate();
    }

    private static void drop(String db) throws Exception {
        try (Connection c = PG.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("select pg_terminate_backend(pid) from pg_stat_activity where datname = '" + db + "' and pid <> pg_backend_pid()");
            s.execute("drop database if exists " + db);
        }
    }

    @Test
    void defaultOrganisationNameBecomesSaAndSessionsAreBackFilled() throws Exception {
        JdbcTemplate jdbc = migrateToV5("r3_migration_default");
        assertThat(jdbc.queryForObject("select setting_value from system_settings where setting_key = 'org.name'", String.class)).isEqualTo("SA 360");
        jdbc.update("insert into users (email, full_name, password_hash, role) values ('a@x.io', 'A', 'x', 'SUPPORT_AGENT')");
        long user = jdbc.queryForObject("select id from users where email = 'a@x.io'", Long.class);
        String open = "11111111-1111-1111-1111-111111111111";
        String closed = "22222222-2222-2222-2222-222222222222";
        jdbc.update("insert into refresh_tokens (user_id, token_hash, family_id, expires_at, revoked_at, replaced_by_hash, user_agent, ip_address, created_at) "
                + "values (?, repeat('a', 64), ?::uuid, now() + interval '7 days', now(), repeat('b', 64), 'Chrome', '10.0.0.1', now() - interval '2 hours'), "
                + "(?, repeat('b', 64), ?::uuid, now() + interval '7 days', null, null, 'Chrome', '10.0.0.2', now() - interval '1 hour'), "
                + "(?, repeat('c', 64), ?::uuid, now() + interval '1 day', now() - interval '1 hour', null, 'Firefox', '10.0.0.3', now() - interval '3 hours')",
                user, open, user, open, user, closed);

        migrate(jdbc);

        assertThat(jdbc.queryForObject("select setting_value from system_settings where setting_key = 'org.name'", String.class)).isEqualTo("SA");
        Map<String, Object> active = jdbc.queryForMap("select * from auth_sessions where family_id = ?::uuid", open);
        assertThat(active).containsEntry("remember_me", true).containsEntry("user_agent", "Chrome").containsEntry("ip_address", "10.0.0.1")
                .containsEntry("last_ip_address", "10.0.0.2");
        assertThat(active.get("revoked_at")).isNull();
        Map<String, Object> ended = jdbc.queryForMap("select * from auth_sessions where family_id = ?::uuid", closed);
        assertThat(ended.get("revoked_at")).isNotNull();
        assertThat(ended).containsEntry("revoked_reason", "LEGACY");

        jdbc.update("insert into users (email, full_name, password_hash, role) values ('c@x.io', 'C', 'x', 'CHANGE_MANAGER'), "
                + "('d@x.io', 'D', 'x', 'ASSET_MANAGER')");
        assertThatThrownBy(() -> jdbc.update("insert into users (email, full_name, password_hash, role) values ('e@x.io', 'E', 'x', 'WIZARD')"))
                .hasMessageContaining("ck_user_role");
        assertThat(jdbc.queryForObject("select public_enabled from status_page_settings where id = 1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from teams", Long.class)).isZero(); // demo teams come from the seeder only
        assertThatThrownBy(() -> jdbc.update("insert into status_subscribers (email, unsubscribe_token_hash, confirm_token_hash) "
                + "values ('s@x.io', 'raw-token', null)")).hasMessageContaining("ck_subscriber");
    }

    @Test
    void customisedOrganisationNameIsKept() throws Exception {
        JdbcTemplate jdbc = migrateToV5("r3_migration_custom");
        jdbc.update("update system_settings set setting_value = 'Acme Shared Services' where setting_key = 'org.name'");
        migrate(jdbc);
        assertThat(jdbc.queryForObject("select setting_value from system_settings where setting_key = 'org.name'", String.class))
                .isEqualTo("Acme Shared Services");
    }
}

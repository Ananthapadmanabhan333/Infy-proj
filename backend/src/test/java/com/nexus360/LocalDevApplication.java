package com.nexus360;

import com.nexus360.support.TestDatabase;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;

/**
 * Runs the API locally against an embedded PostgreSQL 17 (no Docker needed):
 * {@code mvn spring-boot:test-run}. Data is kept in {@code backend/target/local-pg} between runs.
 * Uses the "local" profile (demo seed on, dev-only secrets) — never use this for production.
 */
public final class LocalDevApplication {

    private LocalDevApplication() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("nexus.localPgPort", "54329"));
        EmbeddedPostgres pg = TestDatabase.startPersistent(port, Path.of("target", "local-pg"));
        System.setProperty("DATABASE_URL", pg.getJdbcUrl("postgres", "postgres"));
        System.setProperty("DATABASE_USER", "postgres");
        System.setProperty("DATABASE_PASSWORD", "");
        SpringApplication app = new SpringApplication(NexusApplication.class);
        app.setAdditionalProfiles("local");
        app.run(args);
    }
}

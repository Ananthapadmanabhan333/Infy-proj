package com.nexus360.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * One real PostgreSQL 17 server per JVM (zonky embedded binaries, no Docker), shared by integration tests and the
 * local development launcher.
 */
public final class TestDatabase {

    private static EmbeddedPostgres postgres;

    private TestDatabase() {}

    public static synchronized EmbeddedPostgres get() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // best effort on JVM exit
                }
            }));
        }
        return postgres;
    }

    /** Persistent server for local runs: data survives restarts in {@code dataDir}. */
    public static EmbeddedPostgres startPersistent(int port, Path dataDir) throws IOException {
        return EmbeddedPostgres.builder()
                .setPort(port)
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false)
                .start();
    }

    public static String jdbcUrl() {
        return get().getJdbcUrl("postgres", "postgres");
    }
}

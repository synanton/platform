package org.synanton.synquest.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Assumptions;
import org.synanton.synvault.postgres.PostgresSchema;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Live-Postgres fixture for the quest side (007-2). One container per JVM,
 * same shape as the synvault fixture: canonical DDL installed via
 * {@code PostgresSchema} (DDL-in-one-place — no local copy), tests connect
 * as the least-privilege {@code app} role so every assertion exercises the
 * RLS path, and the engine under test binds {@code app.tenant_id} per
 * operation. Image pin matches {@code pg-poc/001-version-manifest.md}.
 */
abstract class QuestPostgresFixture {

    static final String IMAGE = "pgvector/pgvector:0.8.6-pg16";

    private static volatile boolean started;
    private static PostgreSQLContainer<?> container;

    static {
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    if (container != null) {
                                        try {
                                            container.stop();
                                        } catch (Exception e) {
                                            System.err.println(
                                                    "WARN: quest pg container teardown failed: "
                                                            + e.getMessage());
                                        }
                                    }
                                }));
    }

    protected static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        try {
            container = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE));
            container.start();
            try (Connection admin = adminConnection()) {
                PostgresSchema.ensureSchema(admin);
                try (var stmt = admin.createStatement()) {
                    stmt.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app')"
                            + " THEN CREATE ROLE app NOSUPERUSER LOGIN PASSWORD 'app'; END IF; END $$");
                    stmt.execute("GRANT USAGE ON SCHEMA public TO app");
                    stmt.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO app");
                    stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO app");
                }
            }
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "pg container unavailable, suite skipped: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        }
        started = true;
    }

    protected static PostgresSynquestEngine newEngine() {
        ensureStarted();
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(container.getJdbcUrl());
        ds.setUser("app");
        ds.setPassword("app");
        return new PostgresSynquestEngine(ds);
    }

    protected static Connection appConnection() throws Exception {
        ensureStarted();
        return DriverManager.getConnection(container.getJdbcUrl(), "app", "app");
    }

    static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * Per-class seed hygiene (007-7a): pointers persist across test classes
     * (first-write-wins adoption), so a corpus seed clears its tenants'
     * rows AND pointer rows first — then its own upserts adopt fresh.
     * Contract tests truncate instead (per-test); same property, tighter scope.
     */
    protected static void resetTenants(String... tenants) {
        try (Connection admin = adminConnection();
                var psChunks =
                        admin.prepareStatement("DELETE FROM chunks WHERE tenant_id = ?");
                var psPointers =
                        admin.prepareStatement(
                                "DELETE FROM quest_generations WHERE tenant_id = ?")) {
            for (String tenant : tenants) {
                psChunks.setString(1, tenant);
                psChunks.executeUpdate();
                psPointers.setString(1, tenant);
                psPointers.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("tenant reset failed", e);
        }
    }
}

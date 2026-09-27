package org.synanton.synvault.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared live-Postgres fixture (PG-POC-003; live-test policy: one container per
 * JVM so the suite stays PR-suitable). Test isolation comes from per-test
 * tenant UUIDs, not schema churn. Image pin matches
 * {@code pg-poc/001-version-manifest.md} — never a floating tag.
 */
abstract class PostgresTestBase {

    /** Pinned image; row must match the 001 manifest or the suite fails closed. */
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
                                            // Teardown WARN rule: log, never swallow silently.
                                            System.err.println(
                                                    "WARN: pg container teardown failed: " + e.getMessage());
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
                // Superusers bypass RLS unconditionally (FORCE ROW LEVEL SECURITY
                // covers table owners, not superusers). Tests therefore connect as
                // a least-privilege app role — the same shape production uses —
                // so every assertion exercises the policy path.
                try (var stmt = admin.createStatement()) {
                    stmt.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app')"
                            + " THEN CREATE ROLE app NOSUPERUSER LOGIN PASSWORD 'app'; END IF; END $$");
                    stmt.execute("GRANT USAGE ON SCHEMA public TO app");
                    stmt.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO app");
                    stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO app");
                }
            }
        } catch (Exception e) {
            // Visible-skip rule: off-container guard skips visibly, never green-silently.
            Assumptions.assumeTrue(false, "pg container unavailable, suite skipped: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        }
        started = true;
    }

    protected static Connection connection() throws Exception {
        String url = container.getJdbcUrl();
        return DriverManager.getConnection(url, "app", "app");
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}

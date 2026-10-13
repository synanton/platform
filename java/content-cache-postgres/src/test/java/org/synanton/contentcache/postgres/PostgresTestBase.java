package org.synanton.contentcache.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared live-Postgres fixture (one container per JVM so the suite stays
 * PR-suitable). Test isolation comes from per-test tenant ids, not
 * schema churn. Image pin matches the platform Postgres baseline —
 * never a floating tag.
 */
abstract class PostgresTestBase {

    static final String IMAGE = "postgres:16-alpine";

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
                                            warnTeardown("pg container stop", e);
                                        }
                                    }
                                }));
    }

    static void warnTeardown(String what, Exception e) {
        System.err.println("WARN: " + what + " teardown failed: " + e.getMessage());
    }

    static void abortVisible(String reason) {
        Assumptions.assumeTrue(false, reason);
    }

    protected static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        try {
            container = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE));
            container.start();
            try (Connection admin = adminConnection()) {
                ContentCacheSchema.ensureSchema(admin);
                try (var stmt = admin.createStatement()) {
                    stmt.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app')"
                            + " THEN CREATE ROLE app NOSUPERUSER LOGIN PASSWORD 'app'; END IF; END $$");
                    stmt.execute("GRANT USAGE ON SCHEMA public TO app");
                    stmt.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO app");
                    stmt.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app");
                    stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO app");
                    stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO app");
                }
            }
        } catch (Exception e) {
            abortVisible("pg container unavailable, suite skipped: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        }
        started = true;
    }

    protected static Connection connection() throws Exception {
        return DriverManager.getConnection(container.getJdbcUrl(), "app", "app");
    }

    static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    static String jdbcUrl() {
        return container.getJdbcUrl();
    }
}

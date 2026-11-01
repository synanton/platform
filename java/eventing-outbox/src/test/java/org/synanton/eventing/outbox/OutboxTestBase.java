package org.synanton.eventing.outbox;

import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Shared live-Postgres fixture for the outbox integration/E2E suites (one
 * container per JVM). It applies the real {@code V1__event_outbox.sql} migration,
 * so tests exercise the same schema production consumes. Skips visibly when a
 * container is unavailable rather than failing silently.
 */
abstract class OutboxTestBase {

    static final String POSTGRES_IMAGE = "postgres:16-alpine";

    private static volatile boolean started;
    private static PostgreSQLContainer<?> postgres;

    static {
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    if (postgres != null) {
                                        try {
                                            postgres.stop();
                                        } catch (Exception e) {
                                            System.err.println(
                                                    "WARN: outbox postgres teardown failed: " + e.getMessage());
                                        }
                                    }
                                }));
    }

    protected static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        try {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE));
            postgres.start();
            applySchema();
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "outbox postgres container unavailable, suite skipped: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        }
        started = true;
    }

    protected static DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    protected static Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    protected static void clearOutbox() throws Exception {
        try (Connection admin = connection();
                Statement statement = admin.createStatement()) {
            statement.execute("DELETE FROM event_outbox");
        }
    }

    private static void applySchema() throws Exception {
        String ddl;
        try (InputStream in = OutboxTestBase.class.getResourceAsStream("/db/migration/V1__event_outbox.sql")) {
            if (in == null) {
                throw new IllegalStateException("V1__event_outbox.sql missing from test classpath");
            }
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection admin = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute(ddl);
        }
    }
}

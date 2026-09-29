package org.synanton.synvault.postgres;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-POC-012: lifecycle / quota discipline, verified by test rather than
 * eyeballed. One test per sub-rule: synchronous teardown, teardown-WARN
 * helper, visible-skip helper, DDL-in-one-place. (Quota guard lives in
 * {@code PgQuotaGuardTest}; schema inventory in {@code SchemaInventoryTest}.)
 */
class LifecycleDisciplineTest extends PostgresTestBase {

    @Test
    void teardownIsSynchronous() throws Exception {
        // The newStore() truncate path must take effect immediately: insert a
        // row as app, truncate as admin (same statements the contract-test
        // fixture uses), assert zero without any wait or retry. TRUNCATE is
        // transactional in PG, so this pins "synchronous, never best-effort".
        ensureStarted();
        // SET LOCAL lives until transaction end, so the insert runs inside an
        // explicit transaction — same shape the adapter uses per operation.
        try (Connection app = PostgresTestBase.connection()) {
            app.setAutoCommit(false);
            try (var set = app.createStatement()) {
                set.execute("SET LOCAL app.tenant_id = 'tenant-lifecycle'");
            }
            try (var ps =
                    app.prepareStatement(
                            "INSERT INTO documents"
                                    + " (tenant_id, doc_id, title, source_uri, metadata,"
                                    + " storage_revision, created_at, updated_at)"
                                    + " VALUES ('tenant-lifecycle', 'lc-1', 't', 'cache://lc',"
                                    + " '{}'::jsonb, 0, now(), now())"
                                    + " ON CONFLICT (tenant_id, doc_id) DO NOTHING")) {
                ps.executeUpdate();
            }
            app.commit();
        }
        try (Connection admin = PostgresTestBase.adminConnection();
                var stmt = admin.createStatement()) {
            stmt.execute("TRUNCATE documents, chunks, provenance, publication_log");
        }
        try (Connection admin = PostgresTestBase.adminConnection();
                var ps =
                        admin.prepareStatement(
                                "SELECT count(*) FROM documents WHERE tenant_id = ?")) {
            ps.setString(1, "tenant-lifecycle");
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("truncate took effect synchronously").isZero();
            }
        }
    }

    @Test
    void teardownWarnHelperLogsNeverSwallows() {
        PrintStream original = System.err;
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            PostgresTestBase.warnTeardown("test-widget", new IllegalStateException("boom-42"));
        } finally {
            System.setErr(original);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8))
                .contains("WARN")
                .contains("test-widget")
                .contains("boom-42");
    }

    @Test
    void visibleSkipHelperAbortsWithReason() {
        assertThatThrownBy(() -> PostgresTestBase.abortVisible("off-container because reasons-7"))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("reasons-7");
    }

    @Test
    void ddlLivesInOnePlace() throws Exception {
        // Canonical DDL lives only in schema.sql (loaded by PostgresSchema);
        // no Java source — main or test — may inline DDL statements. The
        // enforcer names what it forbids, so it excludes itself by filename;
        // anything else mentioning DDL fails. Talk about the schema by table
        // name elsewhere, never by restating DDL.
        Path moduleDir = Paths.get("").toAbsolutePath();
        assertThat(moduleDir.resolve("src/main/resources/org/synanton/synvault/postgres/schema.sql"))
                .as("canonical schema.sql exists")
                .exists();
        List<String> violations;
        try (Stream<Path> roots =
                Stream.of(
                        moduleDir.resolve("src/main/java"),
                        moduleDir.resolve("src/test/java"))) {
            violations =
                    roots.flatMap(
                                    root -> {
                                        try {
                                            return Files.walk(root);
                                        } catch (Exception e) {
                                            throw new IllegalStateException(e);
                                        }
                                    })
                            .filter(p -> p.toString().endsWith(".java"))
                            .filter(p -> !p.getFileName().toString().equals(
                                    "LifecycleDisciplineTest.java"))
                            .flatMap(
                                    p -> {
                                        try {
                                            String body = Files.readString(p);
                                            String upper = body.toUpperCase();
                                            if (upper.contains("CREATE TABLE")
                                                    || upper.contains("CREATE POLICY")
                                                    || upper.contains("CREATE INDEX")) {
                                                return Stream.of(
                                                        moduleDir.relativize(p).toString());
                                            }
                                            return Stream.empty();
                                        } catch (Exception e) {
                                            throw new IllegalStateException(e);
                                        }
                                    })
                            .toList();
        }
        assertThat(violations).as("java sources inlining DDL").isEmpty();
    }
}

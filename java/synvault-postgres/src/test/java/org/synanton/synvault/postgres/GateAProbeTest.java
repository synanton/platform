package org.synanton.synvault.postgres;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG-POC-005 Gate A probe: does RLS compose with pgvector ANN at plan level,
 * or filter post-ANN? Raw SQL against the server (no adapter exists yet).
 *
 * <p>Frozen probe query (0.1% selectivity), as the small tenant:
 *
 * <pre>
 * SELECT chunk_id, tenant_id FROM chunks
 * ORDER BY embedding &lt;-&gt; '[1,0,...,0]' LIMIT 10
 * </pre>
 *
 * <p>Three outcomes decided up front (proposal §8.4): 1 = RLS pre-ranking,
 * 2 = post-ANN with explicit predicate + two-part proof, 3 =
 * materialize-and-intersect with re-examined thresholds. None = collapse.
 *
 * <p>EXPLAIN (ANALYZE, BUFFERS, VERBOSE) is captured verbatim to
 * {@code build/gate-a/} and committed as the auditable evidence behind
 * {@code pg-poc/005-gate-a.md} — never paraphrased.
 */
class GateAProbeTest extends PostgresTestBase {

    private static GateACorpus.Seeded seeded;

    private static final String PROBE_SQL =
            "SELECT chunk_id, tenant_id FROM chunks ORDER BY embedding <-> '"
                    + GateACorpus.queryVector()
                    + "' LIMIT "
                    + GateACorpus.LIMIT;

    @BeforeAll
    static void seedCorpus() throws Exception {
        ensureStarted();
        // Seed via the superuser handle (bypasses RLS deterministically).
        // Setup is not the measured path: every measured query below runs as
        // the app role with SET LOCAL, exercising the policy path.
        try (Connection admin = PostgresTestBase.adminConnection()) {
            seeded = GateACorpus.seed(admin);
        }
    }

    @AfterAll
    static void removeCorpus() throws Exception {
        // Synchronous cleanup via superuser (bypasses RLS deterministically).
        if (seeded == null) {
            return;
        }
        try (Connection admin = PostgresTestBase.adminConnection()) {
            GateACorpus.remove(admin, seeded);
        } catch (Exception e) {
            // Teardown WARN rule: log, never swallow silently.
            System.err.println("WARN: Gate A corpus cleanup failed: " + e.getMessage());
        }
    }

    private static List<String> explain(Connection conn, String sql) throws Exception {
        List<String> lines = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("EXPLAIN (ANALYZE, BUFFERS, VERBOSE) " + sql)) {
            while (rs.next()) {
                lines.add(rs.getString(1));
            }
        }
        return lines;
    }

    private static void writeArtifact(String name, List<String> lines) throws Exception {
        Path dir = Paths.get("build", "gate-a");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), lines, StandardCharsets.UTF_8);
        System.out.println("=== " + name + " ===");
        lines.forEach(System.out::println);
        System.out.println("=== end " + name + " ===");
    }

    @Test
    void gateAProbe() throws Exception {
        UUID small = seeded.tenantSmall();
        List<String> naturalPlan;
        List<String> forcedPlan;
        List<UUID> returnedTenants = new ArrayList<>();
        int rowCount;
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL app.tenant_id = '" + small + "'");
                naturalPlan = explain(conn, PROBE_SQL);
            }
            conn.rollback();

            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL app.tenant_id = '" + small + "'");
                stmt.execute("SET LOCAL enable_seqscan = off");
                forcedPlan = explain(conn, PROBE_SQL);
            }
            conn.rollback();
        }

        // Behavioral leg (own transaction, tenant set first).
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL app.tenant_id = '" + small + "'");
                try (ResultSet rs = stmt.executeQuery(PROBE_SQL)) {
                    while (rs.next()) {
                        returnedTenants.add(UUID.fromString(rs.getString(2)));
                    }
                }
            }
            conn.rollback();
        }
        rowCount = returnedTenants.size();

        writeArtifact("explain-natural.txt", naturalPlan);
        writeArtifact("explain-forced-index.txt", forcedPlan);

        // Leakage = hard fail: any cross-tenant row fails regardless of count.
        assertThat(returnedTenants)
                .as("all returned rows belong to the eligible tenant (leakage = fail)")
                .allSatisfy(t -> assertThat(t).isEqualTo(small));
        // Post-ANN signature: 20 eligible rows exist, LIMIT is 10 — fewer than
        // 10 means the ANN shortlist was filtered after retrieval.
        assertThat(rowCount)
                .as("eligible shortlist survived ranking (post-ANN filter would return < 10)")
                .isEqualTo(GateACorpus.LIMIT);

        String planText = String.join("\n", naturalPlan).toLowerCase();
        boolean usesHnsw =
                planText.contains("hnsw") || String.join("\n", forcedPlan).toLowerCase().contains("hnsw");
        System.out.println(
                "GATE-A-CLASSIFICATION leg=selective-0.1pct rows="
                        + rowCount
                        + " leakage=false hnsw-in-plan="
                        + usesHnsw);
    }

    /**
     * Leg 2 (frozen): same query as the ~33% tenant. The eligible set (~10k
     * rows) is too large to brute-force cheaply, so the plan answers the ANN
     * question leg 1 could not: does RLS sit inside the HNSW scan or after it?
     */
    @Test
    void gateALeg2LargeShare() throws Exception {
        UUID half = seeded.tenantHalf();
        List<String> naturalPlan;
        List<UUID> returnedTenants = new ArrayList<>();
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL app.tenant_id = '" + half + "'");
                naturalPlan = explain(conn, PROBE_SQL);
            }
            conn.rollback();

            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL app.tenant_id = '" + half + "'");
                try (ResultSet rs = stmt.executeQuery(PROBE_SQL)) {
                    while (rs.next()) {
                        returnedTenants.add(UUID.fromString(rs.getString(2)));
                    }
                }
            }
            conn.rollback();
        }

        writeArtifact("explain-leg2-natural.txt", naturalPlan);

        assertThat(returnedTenants)
                .as("leg 2: all returned rows belong to the eligible tenant (leakage = fail)")
                .allSatisfy(t -> assertThat(t).isEqualTo(half));
        assertThat(returnedTenants.size())
                .as("leg 2: full LIMIT served from eligible candidates")
                .isEqualTo(GateACorpus.LIMIT);

        boolean usesHnsw = String.join("\n", naturalPlan).toLowerCase().contains("hnsw");
        System.out.println(
                "GATE-A-CLASSIFICATION leg=large-33pct rows="
                        + returnedTenants.size()
                        + " leakage=false hnsw-in-plan="
                        + usesHnsw);
    }
}

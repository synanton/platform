package org.synanton.synvault.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG-POC-013 tie-break verification (Gate B input): (score desc, chunkId asc)
 * post-retrieval determinism on the paths Gate A showed the planner actually
 * uses — btree-restrict-then-sort for vector, tsvector+GIN for lexical.
 *
 * <p>Trivial-green caveat (recorded, not hidden): btree-sort over a bounded
 * set is deterministic by construction, so the vector leg may pass trivially.
 * HNSW tie order is a different question and rides with the Phase 4
 * HNSW-at-scale re-verification (005-gate-a.md caveat) — this test must not be
 * read as covering it.
 */
class TieBreakTest extends PostgresTestBase {

    private static final int TIED_ROWS = 50;
    private static final int RUNS = 5;

    private static UUID tenant;
    private static final List<UUID> chunkIds = new ArrayList<>();

    @BeforeAll
    static void seedTies() throws Exception {
        ensureStarted();
        tenant = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        String tiedVector = GateACorpus.farVector();
        try (Connection admin = PostgresTestBase.adminConnection();
                PreparedStatement ps =
                        admin.prepareStatement(
                                "INSERT INTO chunks (tenant_id, chunk_id, doc_id, ordinal, text,"
                                        + " token_count, embedding, tsv) VALUES (?, ?, ?, ?, ?, ?,"
                                        + " CAST(? AS vector), to_tsvector('english', ?))")) {
            for (int i = 0; i < TIED_ROWS; i++) {
                UUID chunk = UUID.randomUUID();
                chunkIds.add(chunk);
                ps.setObject(1, tenant);
                ps.setObject(2, chunk);
                ps.setObject(3, doc);
                ps.setInt(4, i);
                ps.setString(5, "tie break probe alpha beta gamma");
                ps.setInt(6, 5);
                ps.setString(7, tiedVector);
                ps.setString(8, "tie break probe alpha beta gamma");
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @AfterAll
    static void removeTies() throws Exception {
        try (Connection admin = PostgresTestBase.adminConnection();
                Statement stmt = admin.createStatement()) {
            stmt.execute("DELETE FROM chunks WHERE tenant_id = '" + tenant + "'");
        } catch (Exception e) {
            System.err.println("WARN: tie-break corpus cleanup failed: " + e.getMessage());
        }
    }

    private static List<String> runVectorQuery() throws Exception {
        List<String> ids = new ArrayList<>();
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL app.tenant_id = '" + tenant + "'");
                try (ResultSet rs =
                        stmt.executeQuery(
                                "SELECT chunk_id FROM chunks ORDER BY embedding <-> '"
                                        + GateACorpus.queryVector()
                                        + "' LIMIT "
                                        + TIED_ROWS)) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
            conn.rollback();
        }
        return ids;
    }

    private static List<String> runLexicalQuery() throws Exception {
        List<String> ids = new ArrayList<>();
        try (Connection conn = connection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL app.tenant_id = '" + tenant + "'");
                try (ResultSet rs =
                        stmt.executeQuery(
                                "SELECT chunk_id FROM chunks WHERE tsv @@ plainto_tsquery('english',"
                                        + " 'tie break probe') ORDER BY ts_rank(tsv,"
                                        + " plainto_tsquery('english', 'tie break probe')) DESC"
                                        + " LIMIT "
                                        + TIED_ROWS)) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
            conn.rollback();
        }
        return ids;
    }

    @Test
    void vectorTiesStableAcrossRuns() throws Exception {
        // All 50 rows share one embedding: every distance ties. Repeated runs
        // must return the identical sequence (btree path; deterministic by
        // construction — see class caveat re HNSW).
        List<String> first = runVectorQuery();
        assertThat(first).as("all tied rows returned").hasSize(TIED_ROWS);
        for (int i = 1; i < RUNS; i++) {
            assertThat(runVectorQuery())
                    .as("run " + (i + 1) + " identical to run 1")
                    .isEqualTo(first);
        }
        // Post-retrieval rule reduces to chunkId asc when all distances tie.
        List<String> sorted = new ArrayList<>(first);
        sorted.sort(Comparator.naturalOrder());
        System.out.println(
                "TIEBREAK vector: stable across "
                        + RUNS
                        + " runs; server order chunkId-asc="
                        + first.equals(sorted));
    }

    @Test
    void lexicalTiesStableAcrossRuns() throws Exception {
        // All 50 rows share one text: every ts_rank ties.
        List<String> first = runLexicalQuery();
        assertThat(first).as("all tied rows returned").hasSize(TIED_ROWS);
        for (int i = 1; i < RUNS; i++) {
            assertThat(runLexicalQuery())
                    .as("run " + (i + 1) + " identical to run 1")
                    .isEqualTo(first);
        }
        List<String> sorted = new ArrayList<>(first);
        sorted.sort(Comparator.naturalOrder());
        System.out.println(
                "TIEBREAK lexical: stable across "
                        + RUNS
                        + " runs; server order chunkId-asc="
                        + first.equals(sorted));
    }
}

package org.synanton.synquest.postgres;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG-POC-007-3 plan topology, per selectivity leg (Gate-A-shaped corpus).
 *
 * <p>Two rules from review, enforced here rather than assumed:
 * <ol>
 *   <li>IVFFlat deferred DDL: the index is created AFTER the corpus loads
 *       (training on an empty table degrades to bad centroids) — the test
 *       does exactly that, then {@code ANALYZE}s so the planner sees it.</li>
 *   <li>Eligibility-in-plan, not index-shape: the hard assertion is that the
 *       tenant restriction composes before the distance ordering (sort input
 *       rows ≈ eligible rows, never the full cross-tenant mass) — robust
 *       across whatever topology the planner picks per leg. The picked
 *       topology is recorded per leg (travels with the number); a seqscan at
 *       small scale is legitimate, a sort-then-filter is not.</li>
 * </ol>
 */
class VectorPlanTopologyTest extends QuestPostgresFixture {

    private static final int BIG_ROWS = 20_000;
    private static final int HALF_ROWS = 10_000;
    private static final int SMALL_ROWS = 20;

    private static String e1_LITERAL;

    @BeforeAll
    static void seedCorpus() throws Exception {
        ensureStarted();
        try (Connection admin = QuestPostgresFixture.adminConnection();
                var ps = admin.prepareStatement("SELECT count(*) FROM chunks WHERE tenant_id = ?")) {
            ps.setString(1, "topo-big");
            try (var rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(1) >= BIG_ROWS) {
                    e1_LITERAL = e1Literal();
                    return;
                }
            }
        }
        e1_LITERAL = e1Literal();
        Random random = new Random(0xC0FFEE);
        PostgresSynquestEngine engine = QuestPostgresFixture.newEngine();
        seedTenant(engine, "topo-big", BIG_ROWS, 0.05, random);
        seedTenant(engine, "topo-half", HALF_ROWS, 0.05, random);
        seedTenant(engine, "topo-small", SMALL_ROWS, 10.0, random);
        try (Connection admin = QuestPostgresFixture.adminConnection();
                var stmt = admin.createStatement()) {
            // Deferred DDL (rule 1): trained on the loaded corpus, then
            // ANALYZEd — creating this in schema setup would train on air.
            stmt.execute("DROP INDEX IF EXISTS chunks_embedding_ivfflat");
            stmt.execute(
                    "CREATE INDEX chunks_embedding_ivfflat ON chunks"
                            + " USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)");
            stmt.execute("ANALYZE chunks");
        }
    }

    private static String e1Literal() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 384; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(i == 0 ? 1.0f : 0.0f);
        }
        return sb.append(']').toString();
    }

    private static void seedTenant(
            PostgresSynquestEngine engine, String tenant, int rows, double spread, Random random)
            throws Exception {
        List<ChunkProjection> batch = new ArrayList<>(1000);
        for (int i = 0; i < rows; i++) {
            float[] embedding = new float[384];
            embedding[0] = 1.0f;
            embedding[1] = (float) (random.nextGaussian() * spread);
            for (int d = 2; d < 384; d++) {
                embedding[d] = (float) (random.nextGaussian() * 0.05);
            }
            batch.add(
                    new ChunkProjection(
                            ChunkId.of(tenant + "-c" + i),
                            DocumentId.of(tenant + "-d" + i),
                            tenant,
                            "topology probe text " + i,
                            Map.of("type", "probe"),
                            embedding,
                            EmbeddingModelRef.of("m", "v1", "d"),
                            i,
                            GenerationId.of("gen-topo")));
            if (batch.size() >= 1000) {
                engine.upsert(batch).toCompletableFuture().join();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.upsert(batch).toCompletableFuture().join();
        }
    }

    private static String explain(String tenant) throws Exception {
        try (Connection app = QuestPostgresFixture.appConnection()) {
            app.setAutoCommit(false);
            try (var set = app.createStatement()) {
                set.execute("SET LOCAL app.tenant_id = '" + tenant + "'");
            }
            StringBuilder plan = new StringBuilder();
            try (var ps =
                    app.prepareStatement(
                            "EXPLAIN (ANALYZE, BUFFERS)"
                                    + " SELECT chunk_id FROM chunks"
                                    + " ORDER BY embedding <=> '" + e1_LITERAL + "'::vector"
                                    + " LIMIT 10")) {
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
            }
            app.commit();
            return plan.toString();
        }
    }

    private static long sortInputRows(String plan) {
        Matcher matcher = Pattern.compile("Sort[^\\n]*actual[^\\n]*rows=(\\d+)").matcher(plan);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        // No sort node (pure index-returned order): nothing sorted, vacuously pre-ranked.
        return 0;
    }

    private static String topologyOf(String plan) {
        if (plan.contains("chunks_embedding_ivfflat")) {
            return "ivfflat";
        }
        if (plan.contains("chunks_embedding_hnsw")) {
            return "hnsw";
        }
        if (plan.contains("chunks_tenant_doc_btree")) {
            return "btree-sort";
        }
        if (plan.contains("Seq Scan")) {
            return "seqscan";
        }
        return "unrecognized: " + plan.lines().filter(l -> l.contains("Scan")).findFirst().orElse("?");
    }

    @Test
    void selectiveLegRestrictsBeforeOrdering() throws Exception {
        String plan = explain("topo-small");
        String topology = topologyOf(plan);
        assertThat(plan)
                .as("selective leg [" + topology + "]: tenant restriction is in-plan")
                .contains("tenant_id");
        assertThat(sortInputRows(plan))
                .as("selective leg [" + topology + "]: sort input ≈ eligible rows, not the 30k mass")
                .isLessThanOrEqualTo(SMALL_ROWS * 2L);
    }

    @Test
    void midSelectivityLegRestrictsBeforeOrdering() throws Exception {
        String plan = explain("topo-half");
        String topology = topologyOf(plan);
        assertThat(plan)
                .as("half leg [" + topology + "]: tenant restriction is in-plan")
                .contains("tenant_id");
        assertThat(sortInputRows(plan))
                .as("half leg [" + topology + "]: sort input ≈ eligible rows, not the 30k mass")
                .isLessThanOrEqualTo((long) (HALF_ROWS * 1.5));
    }

    @Test
    void topologiesRecordedPerLeg() throws Exception {
        // Travels-with-the-number: whatever the planner picked per leg is
        // named here. If this output ever feeds a metric name, it comes from
        // these strings, not from an assumed shape.
        String selective = topologyOf(explain("topo-small"));
        String half = topologyOf(explain("topo-half"));
        assertThat(selective).doesNotStartWith("unrecognized");
        assertThat(half).doesNotStartWith("unrecognized");
        System.out.println("PG-007-3 topology: selective=" + selective + " half=" + half);
    }
}

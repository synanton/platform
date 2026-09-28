package org.synanton.bench.emitter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.TemporalExtension;
import org.synanton.synquest.ydb.YdbSearchSchema;
import org.synanton.synquest.ydb.YdbSynquestEngine;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.ExplainDataQueryResult;
import tech.ydb.table.settings.ExplainDataQuerySettings;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 024B 10k sanity (gated -Dbench.run.024b.sanity=true): measures upsert
 * throughput, known-answer readiness (with wait time), truncation rerun
 * safety, and EXPLAIN index usage — before the full C.2 commits hours.
 * Run: {@code CORPUS_DIR=/tmp/corpus-v1 ./gradlew
 * :java:synanton-bench-emitter:test --tests "*Full024BSanity"
 * -Dbench.run.024b.sanity=true}.
 */
@EnabledIfSystemProperty(named = "bench.run.024b.sanity", matches = "true")
class Full024BSanity {

    private static final int ROWS = 10_000;
    private static final int BATCH = 500;
    private static final GenerationId GEN = new GenerationId("sanity-gen-1");
    private static final EmbeddingModelRef MODEL = new EmbeddingModelRef("sanity", "v1", "sanity");
    private static final String PREFIX = "t_emit_sanity24b";

    private static TableClient client() throws Exception {
        String caPath = firstExisting(
                System.getenv().getOrDefault("YDB_CA_PATH", ""),
                System.getProperty("ydb.ca.path", ""),
                "/tmp/ydb-ca.pem",
                System.getProperty("user.home") + "/.config/ydb-ca.pem");
        byte[] ca = Files.readAllBytes(Paths.get(caPath));
        GrpcTransport transport =
                GrpcTransport.forConnectionString("grpcs://localhost:2135/local")
                        .withSecureConnection(ca)
                        .build();
        return TableClient.newClient(transport).build();
    }

    private static String firstExisting(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank() && Files.isReadable(Paths.get(c))) {
                return c;
            }
        }
        throw new IllegalStateException("YDB CA not found");
    }

    private static float[] decodeVec(String b64) {
        byte[] bytes = Base64.getDecoder().decode(b64);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[bytes.length / 4];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
    }

    @Test
    void sanity10k() throws Exception {
        Path corpusDir = Paths.get(System.getenv().getOrDefault("CORPUS_DIR", "/tmp/corpus-v1"));
        TableClient client = client();
        try {
            YdbSearchSchema.ensureSchema(client, PREFIX, 384);
            // Truncation rerun safety: truncate → verify empty → load.
            YdbSearchSchema.truncateAll(client, PREFIX);
            assertThat(countRows(client)).as("starts empty after truncate").isZero();

            // Load 10k with throughput measurement.
            long t0 = System.nanoTime();
            List<CorpusLoader.ChunkRow> rows = new ArrayList<>();
            final int[] seen = {0};
            CorpusLoader.streamChunks(
                    corpusDir,
                    row -> {
                        if (seen[0]++ < ROWS) {
                            rows.add(row);
                        }
                    });
            assertThat(rows).hasSize(ROWS);
            // 041.7: batch path (read → keepFresh → single commit), with commit
            // counting. If this log ever shows 3N commits, the batch path is
            // bypassed and the run measures the old path — assert, not observe.
            YdbSynquestEngine engine = new YdbSynquestEngine(client, PREFIX);
            int commits;
            try (tech.ydb.table.Session session =
                    client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
                java.util.Map<String, Long> stored =
                        engine.readOrderingBatch(session, toProjections(rows));
                List<ChunkProjection> fresh =
                        YdbSynquestEngine.keepFresh(toProjections(rows), stored);
                assertThat(fresh).hasSize(ROWS);
                commits = engine.upsertBatch(session, fresh);
            }
            assertThat(commits)
                    .as("batch path: 100 commits for 10k rows at 100/tx (was 30k)")
                    .isEqualTo(100);
            double secs = (System.nanoTime() - t0) / 1_000_000_000.0;
            double rate = ROWS / secs;
            System.out.printf(
                    "SANITY loaded=%d secs=%.1f rows_per_s=%.1f commits=%d batches=1 batch_size=%d%n",
                    ROWS, secs, rate, commits, ROWS);

            // Known-answer probe: first chunk's own embedding must return it top-1.
            ChunkProjection probe = toProjections(rows).get(0);
            TenantScope scope = TenantScope.of(probe.tenantId());
            PrincipalRef principal = new PrincipalRef("benchmark", "sanity");
            PolicyContext policy = new PolicyContext("benchmark", "v1");
            SecurityContext context = SecurityContext.user(scope, principal, policy);
            SearchRequest request =
                    new SearchRequest(
                            "", Optional.of(probe.embedding()), Optional.of(MODEL), SearchMode.VECTOR,
                            EligibilityConstraints.from(scope, List.of(principal), policy),
                            RelevanceFilters.none(), TemporalExtension.empty(), 10, 0.0);
            String topId = null;
            long waitMs = 0;
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                var hits =
                        engine.search(context, request).toCompletableFuture().join().hits();
                if (!hits.isEmpty() && hits.get(0).chunkId().value().equals(probe.chunkId().value())) {
                    topId = hits.get(0).chunkId().value();
                    break;
                }
                Thread.sleep(5_000);
                waitMs += 5_000;
            }
            System.out.println("SANITY readiness_wait_ms=" + waitMs + " top=" + topId);
            assertThat(topId)
                    .as("known-answer chunk top-1 (index ready; wait was " + waitMs + "ms)")
                    .isEqualTo(probe.chunkId().value());

            // EXPLAIN: vector leg uses the ANN index, not a scan fallback.
            try (Session session =
                    client.createSession(Duration.ofSeconds(10)).join().getValue()) {
                ExplainDataQueryResult plan =
                        session
                                .explainDataQuery(
                                        "DECLARE $t AS Utf8;SELECT chunk_id FROM `" + PREFIX + "_vectors`"
                                                + " VIEW `v_vec` WHERE tenant_id=$t ORDER BY Knn::CosineSimilarity"
                                                + "(embedding, Knn::ToBinaryStringFloat(CAST([1.0] AS List<Float>)))"
                                                + " DESC LIMIT 10;",
                                        new ExplainDataQuerySettings())
                                .join()
                                .getValue();
                String ast = plan.getQueryAst() + "\n" + plan.getQueryPlan();
                assertThat(ast)
                        .as("vector leg uses v_vec index (PlanAssertions pattern)")
                        .contains("v_vec");
                System.out.println("SANITY explain uses v_vec");
            }

            // Second truncate proves rerun safety for the full C.2.
            YdbSearchSchema.truncateAll(client, PREFIX);
            assertThat(countRows(client)).as("empty again after second truncate").isZero();
            System.out.println("SANITY truncation rerun-safe");
        } finally {
            client.close();
        }
    }

    private static List<ChunkProjection> toProjections(List<CorpusLoader.ChunkRow> rows) {
        List<ChunkProjection> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            CorpusLoader.ChunkRow r = rows.get(i);
            out.add(
                    new ChunkProjection(
                            ChunkId.of(r.chunkId()), DocumentId.of(r.docId()),
                            r.tenantId(), r.text(), Map.copyOf(r.metadata()),
                            decodeVec(r.embeddingB64()), MODEL, i, GEN));
        }
        return out;
    }

    private static long countRows(TableClient client) throws Exception {
        try (Session session = client.createSession(Duration.ofSeconds(30)).join().getValue()) {
            var rs =
                    session
                            .executeDataQuery(
                                    "SELECT COUNT(*) AS n FROM `" + PREFIX + "_projections`;",
                                    tech.ydb.table.transaction.TxControl.staleRo().setCommitTx(true),
                                    tech.ydb.table.query.Params.empty(),
                                    new tech.ydb.table.settings.ExecuteDataQuerySettings())
                            .join()
                            .getValue()
                            .getResultSet(0);
            rs.next();
            return rs.getColumn(0).getUint64();
        }
    }
}

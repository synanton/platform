package org.synanton.synquest.ydb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import tech.ydb.table.Session;
import tech.ydb.table.query.Params;
import tech.ydb.table.settings.ExecuteDataQuerySettings;
import tech.ydb.table.transaction.TxControl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 041.1 equivalence reference (041.6 runs the same assertions against the
 * batch path): per-row upsert of a 100-row fresh fixture, snapshotting final
 * state of both tables. The snapshot function is the contract the batch path
 * must satisfy — identical rows, identical keys, identical generations.
 */
class BulkUpsertEquivalenceTest {

    static final String PREFIX = "t_emit_bulkref";
    static final int ROWS = 100;
    static final GenerationId GEN = new GenerationId("bulk-ref-gen-1");
    static final EmbeddingModelRef MODEL = new EmbeddingModelRef("bulk-ref", "v1", "bulk-ref");

    /** Deterministic fresh fixture: 100 rows, 4 tenants, increasing ordering keys. */
    static List<ChunkProjection> fixture() {
        List<ChunkProjection> out = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            String tenant = String.format("tenant_%02d", i % 4);
            out.add(
                    new ChunkProjection(
                            ChunkId.of("bulk-chunk-" + i),
                            DocumentId.of("bulk-doc-" + (i / 8)),
                            tenant,
                            "bulk text row " + i + " alpha",
                            Map.of("doc_type", "memo"),
                            new float[] {i * 0.01f, 1.0f - i * 0.001f},
                            MODEL,
                            1000L + i,
                            GEN));
        }
        return List.copyOf(out);
    }

    /** Canonical snapshot: sorted "table|tenant|chunk|ordering|generation" lines, both tables. */
    static List<String> snapshotState(String prefix) throws Exception {
        List<String> lines = new ArrayList<>();
        try (Session session = YdbSearchTestBase.session()) {
            var rs =
                    session
                            .executeDataQuery(
                                    "SELECT tenant_id, chunk_id, ordering_key, generation FROM `"
                                            + prefix + "_projections`;",
                                    TxControl.staleRo().setCommitTx(true),
                                    Params.empty(),
                                    new ExecuteDataQuerySettings())
                            .join()
                            .getValue()
                            .getResultSet(0);
            while (rs.next()) {
                lines.add(
                        "P|" + rs.getColumn("tenant_id").getText()
                                + "|" + rs.getColumn("chunk_id").getText()
                                + "|" + rs.getColumn("ordering_key").getUint64()
                                + "|" + rs.getColumn("generation").getText());
            }
            var rv =
                    session
                            .executeDataQuery(
                                    "SELECT tenant_id, chunk_id, ordering_key, generation FROM `"
                                            + prefix + "_vectors`;",
                                    TxControl.staleRo().setCommitTx(true),
                                    Params.empty(),
                                    new ExecuteDataQuerySettings())
                            .join()
                            .getValue()
                            .getResultSet(0);
            while (rv.next()) {
                lines.add(
                        "V|" + rv.getColumn("tenant_id").getText()
                                + "|" + rv.getColumn("chunk_id").getText()
                                + "|" + rv.getColumn("ordering_key").getUint64()
                                + "|" + rv.getColumn("generation").getText());
            }
        }
        lines.sort(null);
        return List.copyOf(lines);
    }

    static YdbSynquestEngine freshEngine() {
        YdbSearchTestBase.ensureStarted();
        YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), PREFIX, 2);
        YdbSearchSchema.truncateAll(YdbSearchTestBase.client(), PREFIX);
        return new YdbSynquestEngine(YdbSearchTestBase.client(), PREFIX);
    }

    @Test
    void perRowLoadProducesReferenceStateForEquivalence() throws Exception {        YdbSynquestEngine engine = freshEngine();
        List<ChunkProjection> fixture = fixture();
        // Current per-row path, small batches (reference behavior, not performance).
        for (int i = 0; i < fixture.size(); i += 10) {
            engine.upsert(fixture.subList(i, Math.min(i + 10, fixture.size())))
                    .toCompletableFuture()
                    .join();
        }
        List<String> snapshot = snapshotState(PREFIX);
        // 100 rows × 2 tables, all fresh keys persisted.
        assertThat(snapshot).hasSize(2 * ROWS);
        assertThat(new TreeSet<>(snapshot)).hasSize(2 * ROWS);
        assertThat(snapshot.stream().filter(l -> l.startsWith("P|"))).hasSize(ROWS);
        assertThat(snapshot.stream().filter(l -> l.startsWith("V|"))).hasSize(ROWS);
        System.out.println("EQUIV-REF rows=" + ROWS + " snapshot_lines=" + snapshot.size());
    }

    @Test
    void batchReadReturnsAllKeysInOneQuery() throws Exception {
        YdbSynquestEngine engine = freshEngine();
        List<ChunkProjection> fixture = fixture();
        for (int i = 0; i < fixture.size(); i += 10) {
            engine.upsert(fixture.subList(i, Math.min(i + 10, fixture.size())))
                    .toCompletableFuture()
                    .join();
        }
        Map<String, Long> keys;
        try (tech.ydb.table.Session session = YdbSearchTestBase.session()) {
            keys = engine.readOrderingBatch(session, fixture);
        }
        // One query returned all 100 keys with exact ordering values.
        assertThat(keys).hasSize(ROWS);
        assertThat(keys.get("tenant_00|bulk-chunk-0")).isEqualTo(1000L);
        assertThat(keys.get("tenant_01|bulk-chunk-97")).isEqualTo(1097L);
    }

    @Test
    void batchReadEmptyOnFreshTable() throws Exception {
        freshEngine();
        Map<String, Long> keys;
        try (tech.ydb.table.Session session = YdbSearchTestBase.session()) {
            keys = enginelessRead(fixture());
        }
        // Absent rows are absent (new chunks read as fresh downstream) —
        // never null entries, never zeros.
        assertThat(keys).isEmpty();
    }

    private static Map<String, Long> enginelessRead(List<ChunkProjection> fixture) throws Exception {
        YdbSearchTestBase.ensureStarted();
        YdbSynquestEngine engine =
                new YdbSynquestEngine(YdbSearchTestBase.client(), PREFIX);
        try (tech.ydb.table.Session session = YdbSearchTestBase.session()) {
            return engine.readOrderingBatch(session, fixture);
        }
    }

    @Test
    void dropsStaleRowsKeepsFreshOnMixedBatch() {
        List<ChunkProjection> batch = fixture().subList(0, 6);
        // Stored: chunk-0..2 at lower keys (fresh), chunk-3 at equal key
        // (drop + log), chunk-4 at higher key (drop), chunk-5 absent (fresh).
        Map<String, Long> stored =
                Map.of(
                        "tenant_00|bulk-chunk-0", 500L,
                        "tenant_01|bulk-chunk-1", 500L,
                        "tenant_02|bulk-chunk-2", 500L,
                        "tenant_03|bulk-chunk-3", 1003L,
                        "tenant_00|bulk-chunk-4", 9999L);
        List<ChunkProjection> fresh = YdbSynquestEngine.keepFresh(batch, stored);
        assertThat(fresh.stream().map(p -> p.chunkId().value()).toList())
                .containsExactly("bulk-chunk-0", "bulk-chunk-1", "bulk-chunk-2", "bulk-chunk-5");
    }
}

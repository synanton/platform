package org.synanton.synvault.ydb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.SourceVersionId;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synvault.api.Chunk;
import org.synanton.synvault.api.Document;
import org.synanton.synvault.api.DocumentRevision;
import org.synanton.synvault.api.DocumentWriteOptions;
import org.synanton.synvault.api.ProvenanceRecord;
import org.synanton.synvault.api.PublicationIntent;
import org.synanton.synvault.api.RevisionMetadata;
import org.synanton.synvault.api.RevisionWriteOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YDB-POC-022 write-path operating points. Runs ONLY with
 * {@code -Dydb.bench=true} (excluded from PR runs). Measures revision-commit
 * latency distribution + sustained throughput and metadata-op latency against
 * live YDB.
 *
 * <p>Scope honesty: these are <strong>operating points for Phase-6 cost input,
 * not gates</strong> — no frozen write absolutes exist (Cassandra cannot perform
 * revisions, so there is nothing baseline-relative to gate against). Search legs
 * follow in Phase 2 after 024A/024B.
 */
@EnabledIfSystemProperty(named = "ydb.bench", matches = "true")
class YdbWriteBench {

    private static final int REVISIONS = 100;
    private static final int CHUNKS = 3;
    private static final int META_OPS = 100;

    private static final TenantScope TENANT = TenantScope.of("bench");
    private static final PolicyContext POLICY = PolicyContext.of("p", "r1");

    private static SecurityContext ctx() {
        return SecurityContext.user(TENANT, PrincipalRef.user("u-1"), POLICY);
    }

    private static DocumentRevision revision(String id, int chunks) {
        Document document =
                new Document(
                        DocumentId.of(id), "Title " + id, "cache://art/" + id,
                        Map.of("k", "v"), 0, Instant.now(), Instant.now());
        List<Chunk> chunkList = new ArrayList<>();
        List<ProvenanceRecord> provenance = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            ChunkId chunkId = ChunkId.of(id + ":o" + i);
            chunkList.add(new Chunk(chunkId, document.id(), i, "bench text " + i, 3, Map.of(), null));
            provenance.add(
                    new ProvenanceRecord(
                            chunkId, "bench", SourceVersionId.of("sv-b"),
                            Optional.of(EmbeddingModelRef.of("m", "v1", "d")), 1, 0, 10));
        }
        return new DocumentRevision(
                document, chunkList, provenance,
                new RevisionMetadata(0, PrincipalRef.user("u-1"), Instant.now()),
                new PublicationIntent("rev-" + id + "-" + UUID.randomUUID(), "bench", "{\"op\":\"bench\"}"));
    }

    @Test
    void measureWriteOperatingPoints() {
        YdbTestBase.ensureStarted();
        String prefix = "t_vault_bench";
        YdbSchema.ensureSchema(YdbTestBase.client(), prefix);
        YdbSchema.truncateAll(YdbTestBase.client(), prefix);
        YdbSynvaultStore store = new YdbSynvaultStore(YdbTestBase.client(), prefix, "bench");

        List<Double> revLat = new ArrayList<>();
        for (int i = 0; i < REVISIONS; i++) {
            long s = System.nanoTime();
            store.putDocumentRevision(ctx(), revision(store, i), RevisionWriteOptions.unconditional())
                    .toCompletableFuture()
                    .join();
            revLat.add((System.nanoTime() - s) / 1_000_000.0);
        }
        long t0 = System.nanoTime();
        for (int i = REVISIONS; i < 2 * REVISIONS; i++) {
            store.putDocumentRevision(ctx(), revision(store, i), RevisionWriteOptions.unconditional())
                    .toCompletableFuture()
                    .join();
        }
        double throughput = (REVISIONS / ((System.nanoTime() - t0) / 1_000_000_000.0));

        List<Double> metaLat = new ArrayList<>();
        for (int i = 0; i < META_OPS; i++) {
            Document doc =
                    new Document(
                            DocumentId.of("m-" + i), "t", "cache://m", Map.of(), 0,
                            Instant.now(), Instant.now());
            long s = System.nanoTime();
            store.putDocument(ctx(), doc, DocumentWriteOptions.upserting()).toCompletableFuture().join();
            store.getDocument(ctx(), doc.id()).toCompletableFuture().join();
            metaLat.add((System.nanoTime() - s) / 1_000_000.0);
        }

        System.out.println(
                "BENCH-YDB rev_ms_p50=" + fmt(pct(revLat, 50))
                        + " rev_ms_p95=" + fmt(pct(revLat, 95))
                        + " rev_write_rps=" + fmt(throughput)
                        + " meta_putget_ms_p50=" + fmt(pct(metaLat, 50))
                        + " meta_putget_ms_p95=" + fmt(pct(metaLat, 95))
                        + " revs=" + (2 * REVISIONS));
        assertThat(revLat).hasSize(REVISIONS);
    }

    private static DocumentRevision revision(YdbSynvaultStore store, int i) {
        return revision("w-" + i, CHUNKS);
    }

    private static double pct(List<Double> xs, int p) {
        List<Double> sorted = xs.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * p / 100.0)));
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

}

package org.synanton.synvault.ydb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
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
import org.synanton.synvault.api.ProvenanceRecord;
import org.synanton.synvault.api.PublicationIntent;
import org.synanton.synvault.api.RevisionMetadata;
import org.synanton.synvault.api.RevisionWriteOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 032 write-path subtests (run ahead of 028; no search benchmark needed):
 * ingestion burst + node restart/recovery against 021 + 029.
 */
class YdbWriteResilienceTest {

    private static final TenantScope TENANT = TenantScope.of("tenant_a");
    private static final PolicyContext POLICY = PolicyContext.of("p", "r1");
    private static String prefix;

    private static SecurityContext ctx() {
        return SecurityContext.user(TENANT, PrincipalRef.user("u-1"), POLICY);
    }

    @BeforeAll
    static void ensureSchema() {
        YdbTestBase.ensureStarted();
        prefix = "t_vault_resilience";
        YdbSchema.ensureSchema(YdbTestBase.client(), prefix);
        YdbSchema.truncateAll(YdbTestBase.client(), prefix);
    }

    private YdbSynvaultStore store() {
        return new YdbSynvaultStore(YdbTestBase.client(), prefix, "res-" + UUID.randomUUID());
    }

    private static DocumentRevision revision(String id) {
        Document document =
                new Document(
                        DocumentId.of(id), "Title " + id, "cache://art/" + id,
                        Map.of("k", "v"), 0, Instant.now(), Instant.now());
        List<Chunk> chunks = new ArrayList<>();
        List<ProvenanceRecord> provenance = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ChunkId chunkId = ChunkId.of(id + ":o" + i);
            chunks.add(new Chunk(chunkId, document.id(), i, "burst text " + i, 3, Map.of(), null));
            provenance.add(
                    new ProvenanceRecord(
                            chunkId, "extractor-1", SourceVersionId.of("sv-1"),
                            Optional.of(EmbeddingModelRef.of("m", "v1", "d")), 1, 0, 10));
        }
        return new DocumentRevision(
                document, chunks, provenance,
                new RevisionMetadata(0, PrincipalRef.user("u-1"), Instant.now()),
                new PublicationIntent("rev-" + id + "-" + UUID.randomUUID(), "tenant_a", "{}"));
    }

    @Test
    void ingestionBurstAllCommit() throws Exception {
        YdbSynvaultStore store = store();
        int writers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        long start = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final String id = "burst-" + i;
            futures.add(
                    pool.submit(
                            () ->
                                    store.putDocumentRevision(
                                                    ctx(), revision(id), RevisionWriteOptions.unconditional())
                                            .toCompletableFuture()
                                            .join()));
        }
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        for (int i = 0; i < writers; i++) {
            assertThat(
                            store.getDocument(ctx(), DocumentId.of("burst-" + i))
                                    .toCompletableFuture()
                                    .join())
                    .as("burst doc burst-" + i + " must be complete")
                    .isPresent();
        }
        System.out.println(
                "BURST revs=" + writers + " wall_s=" + String.format("%.2f", seconds)
                        + " rps=" + String.format("%.1f", writers / seconds));
    }

    @Test
    @EnabledIfSystemProperty(named = "ydb.resilience", matches = "true")
    void containerRestartPreservesCommittedAndPending() throws Exception {
        // Stable namespace across the restart: recovery must find the same keys.
        YdbSynvaultStore store =
                new YdbSynvaultStore(YdbTestBase.client(), prefix, "res-restart-" + UUID.randomUUID());
        Process which = new ProcessBuilder("docker", "ps").start();
        assumeThat(which.waitFor(30, TimeUnit.SECONDS) && which.exitValue() == 0)
                .as("docker available")
                .isTrue();
        String revId = "rev-restart-" + UUID.randomUUID();
        Document base = revision("d-restart").document();
        var rev =
                new DocumentRevision(
                        base,
                        revision("d-restart").chunks(),
                        revision("d-restart").provenance(),
                        new RevisionMetadata(0, PrincipalRef.user("u-1"), Instant.now()),
                        new PublicationIntent(revId, "tenant_a", "{}"));
        store.putDocumentRevision(ctx(), rev, RevisionWriteOptions.unconditional())
                .toCompletableFuture()
                .join();

        assertThat(new ProcessBuilder("docker", "restart", "ydb-poc").start().waitFor(120, TimeUnit.SECONDS))
                .isTrue();
        // Wait for the node to accept sessions again (same store identity throughout).
        long deadline = System.currentTimeMillis() + 120_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                store
                        .getDocument(ctx(), DocumentId.of("d-restart"))
                        .toCompletableFuture()
                        .join();
                last = null;
                break;
            } catch (Exception e) {
                last = e;
                Thread.sleep(3000);
            }
        }
        assertThat(last).as("node must recover").isNull();
        assertThat(
                        store
                                .getDocument(ctx(), DocumentId.of("d-restart"))
                                .toCompletableFuture()
                                .join())
                .as("committed revision survives restart")
                .isPresent();
        assertThat(
                        store.pendingPublications(ctx(), 10).toCompletableFuture().join())
                .extracting(PublicationIntent::revisionId)
                .as("unpublished record survives restart for relay resume")
                .contains(revId);
    }

}

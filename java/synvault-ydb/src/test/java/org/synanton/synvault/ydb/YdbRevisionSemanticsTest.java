package org.synanton.synvault.ydb;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.PageRequest;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synvault.api.ChunkQuery;
import org.synanton.synvault.api.DocumentRevision;
import org.synanton.synvault.api.RevisionWriteOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 021 exit extras beyond the shared contract: injected-failure atomicity,
 * concurrent-commit integrity, out-of-order pagination, and 039 acceptance of
 * YDB where revision semantics are required (the inverse of the Cassandra case).
 */
class YdbRevisionSemanticsTest {

    private static final TenantScope TENANT = TenantScope.of("tenant_a");
    private static final PolicyContext POLICY = PolicyContext.of("p", "r1");
    private static String prefix;

    private static SecurityContext ctx() {
        return SecurityContext.user(TENANT, PrincipalRef.user("u-1"), POLICY);
    }

    @BeforeAll
    static void ensureSchema() {
        YdbTestBase.ensureStarted();
        prefix = YdbTestBase.randomPrefix();
        YdbTestBase.trackedSchema(prefix);
    }

    private YdbSynvaultStore store() {
        return new YdbSynvaultStore(YdbTestBase.client(), prefix, "sem-" + UUID.randomUUID());
    }

    private static DocumentRevision revision(YdbSynvaultStore store, String id, int chunks) {
        // Reuse the contract's revision builder shape via a throwaway in-memory roundtrip is
        // overkill; build directly with the same field semantics.
        var document =
                new org.synanton.synvault.api.Document(
                        DocumentId.of(id),
                        "Title " + id,
                        "cache://art/" + id,
                        java.util.Map.of("k", "v"),
                        0,
                        java.time.Instant.now(),
                        java.time.Instant.now());
        var chunkList = new java.util.ArrayList<org.synanton.synvault.api.Chunk>();
        var provenance = new java.util.ArrayList<org.synanton.synvault.api.ProvenanceRecord>();
        for (int i = 0; i < chunks; i++) {
            var chunkId = org.synanton.storage.contract.ChunkId.of(id + ":o" + i);
            chunkList.add(
                    new org.synanton.synvault.api.Chunk(
                            chunkId, document.id(), i, "text " + i, 3, java.util.Map.of(), null));
            provenance.add(
                    new org.synanton.synvault.api.ProvenanceRecord(
                            chunkId,
                            "extractor-1",
                            org.synanton.storage.contract.SourceVersionId.of("sv-1"),
                            Optional.of(
                                    org.synanton.storage.contract.EmbeddingModelRef.of("m", "v1", "d")),
                            1,
                            0,
                            6));
        }
        return new DocumentRevision(
                document,
                chunkList,
                provenance,
                new org.synanton.synvault.api.RevisionMetadata(
                        0, PrincipalRef.user("u-1"), java.time.Instant.now()),
                new org.synanton.synvault.api.PublicationIntent(
                        "rev-" + id + "-" + UUID.randomUUID(), "tenant_a", "{\"op\":\"upsert\"}"));
    }

    @Test
    void injectedFailureLeavesNoPartialState() {
        YdbSynvaultStore store = store();
        store.failAfterChunks = true;
        try {
            store.putDocumentRevision(ctx(), revision(store, "d-fail", 3), RevisionWriteOptions.unconditional())
                    .toCompletableFuture()
                    .join();
            assertThat(false).as("expected failure").isTrue();
        } catch (Exception e) {
            // expected: injected failure aborts the transaction
        } finally {
            store.failAfterChunks = false;
        }
        assertThat(store.getDocument(ctx(), DocumentId.of("d-fail")).toCompletableFuture().join())
                .as("rolled-back revision must leave nothing visible")
                .isEmpty();
        assertThat(
                        store.getChunks(ctx(), DocumentId.of("d-fail"), ChunkQuery.all(), PageRequest.first(10))
                                .toCompletableFuture()
                                .join()
                                .items())
                .isEmpty();
    }

    @Test
    void concurrentCommitsLeaveExactlyOneCompleteRevision() throws Exception {
        YdbSynvaultStore store = store();
        store.putDocumentRevision(ctx(), revision(store, "d-race", 2), RevisionWriteOptions.unconditional())
                .toCompletableFuture()
                .join();
        long committed =
                store.getDocument(ctx(), DocumentId.of("d-race")).toCompletableFuture().join().orElseThrow()
                        .storageRevision();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();
        Future<?> a =
                pool.submit(
                        () -> {
                            ready.countDown();
                            try {
                                go.await(10, TimeUnit.SECONDS);
                                store.putDocumentRevision(
                                                ctx(), revision(store, "d-race", 2),
                                                RevisionWriteOptions.expectRevision(committed))
                                        .toCompletableFuture()
                                        .join();
                            } catch (Throwable t) {
                                failureA.set(t);
                            }
                        });
        Future<?> b =
                pool.submit(
                        () -> {
                            ready.countDown();
                            try {
                                go.await(10, TimeUnit.SECONDS);
                                store.putDocumentRevision(
                                                ctx(), revision(store, "d-race", 2),
                                                RevisionWriteOptions.expectRevision(committed))
                                        .toCompletableFuture()
                                        .join();
                            } catch (Throwable t) {
                                failureB.set(t);
                            }
                        });
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        a.get(60, TimeUnit.SECONDS);
        b.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        int failures = (failureA.get() == null ? 0 : 1) + (failureB.get() == null ? 0 : 1);
        Throwable loser = failureA.get() != null ? failureA.get() : failureB.get();
        assertThat(failures)
                .as("exactly one racer must lose; A=" + failureA.get() + " B=" + failureB.get())
                .isEqualTo(1);
        // The loser surfaces as CONFLICT either via the Java OCC check or via the YDB
        // serializable abort mapping — retry-after-reread is correct in both cases.
        assertThat(String.valueOf(loser.getMessage())).contains("CONFLICT");
        // Prescribed recovery: re-read the new expected revision and retry — must commit.
        long reread =
                store.getDocument(ctx(), DocumentId.of("d-race")).toCompletableFuture().join().orElseThrow()
                        .storageRevision();
        store.putDocumentRevision(
                        ctx(), revision(store, "d-race", 2), RevisionWriteOptions.expectRevision(reread))
                .toCompletableFuture()
                .join();
        // Final state: latest revision complete, no partial state from any attempt.
        assertThat(
                        store.getChunks(ctx(), DocumentId.of("d-race"), ChunkQuery.all(), PageRequest.first(10))
                                .toCompletableFuture()
                                .join()
                                .items())
                .hasSize(2);
        assertThat(store.getProvenance(ctx(), DocumentId.of("d-race")).toCompletableFuture().join())
                .hasSize(2);
        // Winner is complete: doc + all chunks + all provenance.
        assertThat(store.getDocument(ctx(), DocumentId.of("d-race")).toCompletableFuture().join())
                .isPresent();
        assertThat(
                        store.getChunks(ctx(), DocumentId.of("d-race"), ChunkQuery.all(), PageRequest.first(10))
                                .toCompletableFuture()
                                .join()
                                .items())
                .hasSize(2);
        assertThat(store.getProvenance(ctx(), DocumentId.of("d-race")).toCompletableFuture().join())
                .hasSize(2);
    }

    @Test
    void cursorBeyondEndReturnsEmptyPage() {
        YdbSynvaultStore store = store();
        store.putDocumentRevision(ctx(), revision(store, "d-page", 2), RevisionWriteOptions.unconditional())
                .toCompletableFuture()
                .join();
        var page =
                store.getChunks(ctx(), DocumentId.of("d-page"), ChunkQuery.all(), PageRequest.after(10, "99"))
                        .toCompletableFuture()
                        .join();
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isEmpty();
    }

    @Test
    void startupValidationAcceptsYdbWhereRevisionRequired() {
        var registry = new org.synanton.storage.provider.ProviderRegistry();
        var adapter = new YdbSynvaultStore(YdbTestBase.client(), prefix, "sem-" + UUID.randomUUID());
        registry.register(org.synanton.storage.provider.ProviderRegistry.PORT_SYNVAULT, "ydb", adapter);
        var quest = new org.synanton.synquest.inmemory.InMemorySynquestEngine();
        registry.register(org.synanton.storage.provider.ProviderRegistry.PORT_SYNQUEST, "inmemory", quest);
        registry.register(org.synanton.storage.provider.ProviderRegistry.PORT_WRITER, "inmemory", quest);
        registry.register(org.synanton.storage.provider.ProviderRegistry.PORT_ADMIN, "inmemory", quest);
        var validated =
                registry.validate(
                        new org.synanton.storage.provider.ProviderSelection(
                                "ydb", "inmemory", "inmemory", "inmemory"),
                        org.synanton.storage.provider.DeploymentRequirements.fullRevision(false));
        org.assertj.core.api.Assertions.assertThat(validated.describe()).contains("synvault=ydb@1.0.0");
    }
}

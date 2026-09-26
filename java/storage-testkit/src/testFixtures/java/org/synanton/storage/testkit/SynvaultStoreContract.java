package org.synanton.storage.testkit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.PageRequest;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.SourceVersionId;
import org.synanton.storage.contract.StorageErrorKind;
import org.synanton.storage.contract.StorageException;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synvault.api.Chunk;
import org.synanton.synvault.api.ChunkPage;
import org.synanton.synvault.api.ChunkQuery;
import org.synanton.synvault.api.Document;
import org.synanton.synvault.api.DocumentRevision;
import org.synanton.synvault.api.DocumentWriteOptions;
import org.synanton.synvault.api.ProvenanceRecord;
import org.synanton.synvault.api.PublicationIntent;
import org.synanton.synvault.api.RevisionMetadata;
import org.synanton.synvault.api.RevisionWriteOptions;
import org.synanton.synvault.api.SynvaultStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter-agnostic contract for {@link SynvaultStore} (YDB-POC-013/014, proposal §9.1).
 * Concrete adapters subclass and supply a fresh store per test. Behavior branches on
 * the adapter's own {@code capabilities()} — exactly the §9.2/020 mechanism.
 */
public abstract class SynvaultStoreContract {

    protected abstract SynvaultStore newStore();

    protected static final TenantScope TENANT_A = TenantScope.of("tenant_a");
    protected static final TenantScope TENANT_B = TenantScope.of("tenant_b");
    protected static final PolicyContext POLICY = PolicyContext.of("p", "r1");

    protected static SecurityContext ctx(TenantScope tenant) {
        return SecurityContext.user(tenant, PrincipalRef.user("u-1"), POLICY);
    }

    protected static Document doc(String id) {
        Instant now = Instant.now();
        return new Document(
                DocumentId.of(id), "Title " + id, "cache://art/" + id, Map.of("k", "v"), 0, now, now);
    }

    protected static DocumentRevision revision(String id, int chunks) {
        Document document = doc(id);
        var chunkList = new java.util.ArrayList<Chunk>();
        var provenance = new java.util.ArrayList<ProvenanceRecord>();
        for (int i = 0; i < chunks; i++) {
            var chunkId = org.synanton.storage.contract.ChunkId.of(id + "-c" + i);
            chunkList.add(
                    new Chunk(chunkId, document.id(), i, "text " + i, 3, Map.of(), null));
            provenance.add(
                    new ProvenanceRecord(
                            chunkId,
                            "extractor-1",
                            SourceVersionId.of("sv-1"),
                            java.util.Optional.of(EmbeddingModelRef.of("m", "v1", "d")),
                            1,
                            0,
                            6));
        }
        return new DocumentRevision(
                document,
                chunkList,
                provenance,
                new RevisionMetadata(0, PrincipalRef.user("u-1"), Instant.now()),
                new PublicationIntent("rev-" + id, "tenant_a", "{\"op\":\"upsert\"}"));
    }

    @Test
    void putAndGetDocumentRoundtrip() {
        SynvaultStore store = newStore();
        Document saved = store.putDocument(ctx(TENANT_A), doc("d1"), DocumentWriteOptions.upserting()).toCompletableFuture().join();
        assertThat(saved.id().value()).isEqualTo("d1");
        assertThat(store.getDocument(ctx(TENANT_A), DocumentId.of("d1")).toCompletableFuture().join())
                .isPresent();
    }

    @Test
    void putDocumentIsMetadataOnly() {
        SynvaultStore store = newStore();
        store.putDocument(ctx(TENANT_A), doc("d1"), DocumentWriteOptions.upserting()).toCompletableFuture().join();
        ChunkPage page =
                store.getChunks(ctx(TENANT_A), DocumentId.of("d1"), ChunkQuery.all(), PageRequest.first(10))
                        .toCompletableFuture()
                        .join();
        assertThat(page.items()).isEmpty();
        assertThat(store.getProvenance(ctx(TENANT_A), DocumentId.of("d1")).toCompletableFuture().join())
                .isEmpty();
    }

    @Test
    void revisionCommitsAtomicallyWhenSupported() {
        SynvaultStore store = newStore();
        if (!store.capabilities().supportsTransactions()) {
            assertThatThrownBy(
                            () -> store.putDocumentRevision(ctx(TENANT_A), revision("d1", 2), RevisionWriteOptions.unconditional())
                                    .toCompletableFuture()
                                    .join())
                    .hasStackTraceContaining(StorageErrorKind.UNSUPPORTED.name());
            return;
        }
        store.putDocumentRevision(ctx(TENANT_A), revision("d1", 2), RevisionWriteOptions.unconditional())
                .toCompletableFuture()
                .join();
        assertThat(store.getDocument(ctx(TENANT_A), DocumentId.of("d1")).toCompletableFuture().join())
                .isPresent();
        assertThat(
                        store.getChunks(ctx(TENANT_A), DocumentId.of("d1"), ChunkQuery.all(), PageRequest.first(10))
                                .toCompletableFuture()
                                .join()
                                .items())
                .hasSize(2);
        assertThat(store.getProvenance(ctx(TENANT_A), DocumentId.of("d1")).toCompletableFuture().join())
                .hasSize(2);
    }

    @Test
    void provenanceIsMandatoryAtTypeLevel() {
        Document document = doc("d1");
        assertThatThrownBy(
                        () ->
                                new DocumentRevision(
                                        document,
                                        List.of(),
                                        List.of(),
                                        new RevisionMetadata(0, PrincipalRef.user("u-1"), Instant.now()),
                                        new PublicationIntent("r", "tenant_a", "{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optimisticConcurrencyConflict() {
        SynvaultStore store = newStore();
        if (!store.capabilities().supportsStorageRevisions()
                || !store.capabilities().supportsTransactions()) {
            return;
        }
        store.putDocumentRevision(ctx(TENANT_A), revision("d1", 1), RevisionWriteOptions.unconditional())
                .toCompletableFuture()
                .join();
        long committed =
                store.getDocument(ctx(TENANT_A), DocumentId.of("d1")).toCompletableFuture().join().orElseThrow()
                        .storageRevision();
        assertThatThrownBy(
                        () ->
                                store.putDocumentRevision(
                                                ctx(TENANT_A), revision("d1", 1), RevisionWriteOptions.expectRevision(committed + 99))
                                        .toCompletableFuture()
                                        .join())
                .hasStackTraceContaining(StorageErrorKind.CONFLICT.name());
    }

    @Test
    void chunksPaginateWithStableOrder() {
        SynvaultStore store = newStore();
        if (!store.capabilities().supportsTransactions()) {
            return;
        }
        store.putDocumentRevision(ctx(TENANT_A), revision("d1", 5), RevisionWriteOptions.unconditional())
                .toCompletableFuture()
                .join();
        ChunkPage first =
                store.getChunks(ctx(TENANT_A), DocumentId.of("d1"), ChunkQuery.all(), PageRequest.first(2))
                        .toCompletableFuture()
                        .join();
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isPresent();
        ChunkPage second =
                store.getChunks(
                                ctx(TENANT_A), DocumentId.of("d1"), ChunkQuery.all(), PageRequest.after(2, first.nextCursor().orElseThrow()))
                        .toCompletableFuture()
                        .join();
        assertThat(second.items()).hasSize(2);
        assertThat(second.items().get(0).ordinal()).isEqualTo(2);
    }

    @Test
    void tenantIsolationHolds() {
        SynvaultStore store = newStore();
        store.putDocument(ctx(TENANT_A), doc("d1"), DocumentWriteOptions.upserting()).toCompletableFuture().join();
        assertThat(store.getDocument(ctx(TENANT_B), DocumentId.of("d1")).toCompletableFuture().join())
                .isEmpty();
        assertThat(
                        store.getChunks(ctx(TENANT_B), DocumentId.of("d1"), ChunkQuery.all(), PageRequest.first(10))
                                .toCompletableFuture()
                                .join()
                                .items())
                .isEmpty();
    }

    @Test
    void capabilitiesMeetPocMinimum() {
        SynvaultStore store = newStore();
        assertThat(store.capabilities().supportsProvenance()).isTrue();
        assertThat(store.capabilities().supportsCursorPagination()).isTrue();
    }
}

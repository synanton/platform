package org.synanton.synvault.ydb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.FreshnessLag;
import org.synanton.storage.contract.GenerationId;
import org.synanton.storage.contract.InMemoryFreshnessTracker;
import org.synanton.storage.contract.PolicyContext;import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.SourceVersionId;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.TemporalExtension;
import org.synanton.synquest.ydb.YdbSynquestEngine;
import org.synanton.synvault.api.Chunk;
import org.synanton.synvault.api.ChunkPage;
import org.synanton.synvault.api.ChunkQuery;
import org.synanton.synvault.api.Document;
import org.synanton.synvault.api.DocumentRevision;
import org.synanton.storage.contract.PageRequest;
import org.synanton.synvault.api.ProvenanceRecord;
import org.synanton.synvault.api.PublicationIntent;
import org.synanton.synvault.api.RevisionMetadata;
import org.synanton.synvault.api.RevisionWriteOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 029 projection-consistency path on live YDB (harness relay standing in for the
 * future 1.27 client): commit → tenant-scoped pending → project → search-visible
 * → markPublished, with freshness pairing. 031 tenant-scoping verified inline.
 */
class YdbProjectionRelayTest {

    private static final TenantScope TENANT_A = TenantScope.of("tenant_a");
    private static final TenantScope TENANT_B = TenantScope.of("tenant_b");
    private static final PolicyContext POLICY = PolicyContext.of("p", "r1");
    private static String vaultPrefix;
    private static String searchPrefix;

    private static SecurityContext ctx(TenantScope tenant) {
        return SecurityContext.user(tenant, PrincipalRef.user("u-1"), POLICY);
    }

    @BeforeAll
    static void ensureSchemas() {
        YdbTestBase.ensureStarted();
        vaultPrefix = YdbTestBase.randomPrefix();
        YdbSchema.ensureSchema(YdbTestBase.client(), vaultPrefix);
        searchPrefix = YdbTestBase.randomPrefix();
        // Search schema owned by synquest-ydb main (no local copy — copies drift;
        // see P0-1 relay incident). Dim is irrelevant here (lexical-only relay).
        org.synanton.synquest.ydb.YdbSearchSchema.ensureSchema(
                YdbTestBase.client(), searchPrefix, 2);
    }

    private YdbSynvaultStore store(String ns) {
        return new YdbSynvaultStore(YdbTestBase.client(), vaultPrefix, ns);
    }

    private static DocumentRevision revision(String id, String revId) {
        Document document =
                new Document(
                        DocumentId.of(id), "Title " + id, "cache://art/" + id,
                        Map.of("k", "v"), 0, Instant.now(), Instant.now());
        List<Chunk> chunks = new ArrayList<>();
        List<ProvenanceRecord> provenance = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ChunkId chunkId = ChunkId.of(id + ":o" + i);
            chunks.add(new Chunk(chunkId, document.id(), i, "relay probe text " + i, 3, Map.of(), null));
            provenance.add(
                    new ProvenanceRecord(
                            chunkId, "extractor-1", SourceVersionId.of("sv-1"),
                            Optional.of(EmbeddingModelRef.of("m", "v1", "d")), 1, 0, 10));
        }
        return new DocumentRevision(
                document, chunks, provenance,
                new RevisionMetadata(0, PrincipalRef.user("u-1"), Instant.now()),
                new PublicationIntent(revId, "tenant_a", "{\"op\":\"relay\"}"));
    }

    @Test
    void commitToSearchVisibleWithFreshness() {
        String ns = "relay-" + UUID.randomUUID();
        YdbSynvaultStore store = store(ns);
        YdbSynquestEngine engine = new YdbSynquestEngine(YdbTestBase.client(), searchPrefix);
        InMemoryFreshnessTracker tracker = new InMemoryFreshnessTracker();
        String revId = "rev-relay-" + UUID.randomUUID();

        // 1. Commit + record.
        long commitAt = System.currentTimeMillis();
        store.putDocumentRevision(ctx(TENANT_A), revision("d-relay", revId), RevisionWriteOptions.unconditional())
                .toCompletableFuture()
                .join();
        tracker.recordCommit(revId, commitAt);

        // 2. Tenant-scoped pending (031): A sees its own; B sees only B's (none yet).
        var pendingA = store.pendingPublications(ctx(TENANT_A), 10).toCompletableFuture().join();
        assertThat(pendingA).extracting(PublicationIntent::revisionId).contains(revId);
        // Seed B's record through the same tables, other tenant.
        String revB = "rev-relay-b-" + UUID.randomUUID();
        store.putDocumentRevision(ctx(TENANT_B), revision("d-relay-b", revB), RevisionWriteOptions.unconditional())
                .toCompletableFuture()
                .join();
        var pendingB = store.pendingPublications(ctx(TENANT_B), 10).toCompletableFuture().join();
        assertThat(pendingB).extracting(PublicationIntent::revisionId)
                .contains(revB)
                .doesNotContain(revId);
        assertThat(store.pendingPublications(ctx(TENANT_A), 10).toCompletableFuture().join())
                .extracting(PublicationIntent::revisionId)
                .doesNotContain(revB);

        // 3. Project: ordering key derives from the Synvault commit sequence.
        Document committed =
                store.getDocument(ctx(TENANT_A), DocumentId.of("d-relay")).toCompletableFuture().join()
                        .orElseThrow();
        ChunkPage chunks =
                store.getChunks(
                                ctx(TENANT_A), DocumentId.of("d-relay"), ChunkQuery.all(),
                                PageRequest.first(10))
                        .toCompletableFuture()
                        .join();
        GenerationId gen = GenerationId.of("gen-relay-1");
        List<ChunkProjection> projections =
                chunks.items().stream()
                        .map(
                                c ->
                                        new ChunkProjection(
                                                c.id(), c.documentId(), TENANT_A.tenantId(), c.text(),
                                                c.metadata(), c.embedding(),
                                                EmbeddingModelRef.of("m", "v1", "d"),
                                                committed.storageRevision(), gen))
                        .toList();
        assertThat(projections)
                .as("ordering key must be the commit sequence revision")
                .allSatisfy(p -> assertThat(p.orderingKey()).isEqualTo(committed.storageRevision()));
        engine.upsert(projections).toCompletableFuture().join();

        // 4. Search-visible + mark + freshness pair.
        var result =
                engine.search(
                                ctx(TENANT_A),
                                new SearchRequest(
                                        "relay probe",
                                        Optional.empty(),
                                        Optional.empty(),
                                        SearchMode.LEXICAL,
                                        EligibilityConstraints.from(
                                                TENANT_A, List.of(PrincipalRef.user("u-1")), POLICY),
                                        RelevanceFilters.none(),
                                        TemporalExtension.empty(),
                                        10,
                                        0.0))
                        .toCompletableFuture()
                        .join();
        assertThat(result.hits()).hasSize(3);
        long visibleAt = System.currentTimeMillis();
        store.markPublished(ctx(TENANT_A), revId).toCompletableFuture().join();
        tracker.recordVisible(revId, visibleAt);

        FreshnessLag lag = tracker.lag();
        assertThat(lag.lastLagMs()).isPresent();
        assertThat(store.pendingPublications(ctx(TENANT_A), 10).toCompletableFuture().join())
                .extracting(PublicationIntent::revisionId)
                .doesNotContain(revId);
        // Re-mark is idempotent.
        store.markPublished(ctx(TENANT_A), revId).toCompletableFuture().join();
        System.out.println(
                "FRESH commit_to_visible_ms=" + lag.lastLagMs().orElse(-1L)
                        + " pending=" + lag.pendingCommits());
    }
}

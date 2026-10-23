package org.synanton.synquest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synquest.config.SynquestProperties;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #255 projection/query acceptance: deterministic projection from canonical
 * knowledge, duplicate/out-of-order projection convergence, rebuild, no-result,
 * and cross-tenant denial (candidate generation is tenant-scoped — B's query
 * never returns A's chunks).
 */
class LuceneKnowledgeSearchBackendTest {

    private static final String TERM = "supply-chain";
    private static final String TEXT = "The " + TERM + " policy governs European logistics.";

    @TempDir
    Path indexRoot;

    private IngestionCacheClient cache;
    private LuceneKnowledgeSearchBackend backend;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        cache = mock(IngestionCacheClient.class);
        var props = new SynquestProperties(
                new SynquestProperties.Index(indexRoot.toString(), false, 30), null, null, null);
        var indexBuilder = new LuceneIndexBuilder(cache, props);
        searchService = new SearchService(indexBuilder, mock(QueryEmbedder.class), props);
        backend = new LuceneKnowledgeSearchBackend(indexBuilder, searchService, cache, props);
    }

    private static ManifestRow manifest(String tenantId, UUID contentRefId, String resource) {
        return new ManifestRow(tenantId, contentRefId, Instant.now(), 1, "semantic-v1", 1,
                "CHUNKED", "CONTENT_CACHE", "", "file:///" + resource, "sha256:" + resource, TEXT.length(),
                "text/markdown", "FULL", null, "");
    }

    private static ChunkRow chunk(String tenantId, UUID contentRefId, String text, Set<UUID> versions) {
        return new ChunkRow(tenantId, contentRefId, 0, text, "chunksha-" + Math.abs(text.hashCode()),
                1, 1, "Section", "SECTION", "Section", "[\"element-1\"]", 8, "", false,
                ChunkRow.PUBLIC_ONLY, "", "", 0, versions, null);
    }

    private void seedCanonical(String tenantId, UUID contentRefId, String resource, String text, UUID versionId) {
        when(cache.listManifest(tenantId, 200_000))
                .thenReturn(List.of(manifest(tenantId, contentRefId, resource)));
        when(cache.readChunks(tenantId, contentRefId))
                .thenReturn(List.of(chunk(tenantId, contentRefId, text, Set.of(versionId))));
    }

    @Test
    void projectMakesCanonicalChunkSearchableWithStableReferences() throws Exception {
        String tenant = "tenant-search-" + UUID.randomUUID();
        UUID contentRefId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        seedCanonical(tenant, contentRefId, "doc.md", TEXT, versionId);

        int projected = backend.project(tenant);
        assertThat(projected).isEqualTo(1);
        assertThat(backend.isReady(tenant)).isTrue();

        var candidates = backend.candidates(tenant, TERM, 10);
        assertThat(candidates).hasSize(1);
        var candidate = candidates.getFirst();
        assertThat(candidate.objectId()).isEqualTo(contentRefId + ":0");
        assertThat(candidate.contentRefId()).isEqualTo(contentRefId);
        assertThat(candidate.sourceVersionIds()).containsExactly(versionId);
        assertThat(candidate.provenanceRef())
                .isEqualTo("/v1/knowledge/" + candidate.objectId() + "/provenance");
        assertThat(candidate.title()).isNotBlank();
        assertThat(candidate.snippet()).isNotBlank();
    }

    @Test
    void duplicateProjectionIsIdempotentAndUnknownTermsReturnNoResults() throws Exception {
        String tenant = "tenant-search-dup-" + UUID.randomUUID();
        UUID contentRefId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        seedCanonical(tenant, contentRefId, "doc.md", TEXT, versionId);

        backend.project(tenant);
        backend.project(tenant);

        assertThat(backend.candidates(tenant, TERM, 10)).hasSize(1);
        assertThat(backend.candidates(tenant, "zzzqqq-xyzzy", 10)).isEmpty();
    }

    @Test
    void outOfOrderProjectionConvergesOnCanonicalState() throws Exception {
        String tenant = "tenant-search-order-" + UUID.randomUUID();
        UUID contentRefId = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        String textB = "Older revision without the matching term.";

        // Out of order: the newer revision projects before the older one arrives —
        // projection is a full rebuild from canonical knowledge, so the last
        // projected canonical state wins and duplicates converge.
        when(cache.listManifest(tenant, 200_000))
                .thenReturn(List.of(manifest(tenant, contentRefId, "doc.md")))
                .thenReturn(List.of(manifest(tenant, contentRefId, "doc.md")));
        when(cache.readChunks(tenant, contentRefId))
                .thenReturn(List.of(chunk(tenant, contentRefId, textB, Set.of(versionB))))
                .thenReturn(List.of(chunk(tenant, contentRefId, TEXT, Set.of(versionA(tenant)))));

        backend.project(tenant);
        backend.project(tenant);

        var candidates = backend.candidates(tenant, TERM, 10);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().sourceVersionIds()).containsExactly(versionA(tenant));
    }

    private UUID versionA(String tenant) {
        return UUID.nameUUIDFromBytes(("A:" + tenant).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void rebuildPicksUpNewCanonicalContent() throws Exception {
        String tenant = "tenant-search-rebuild-" + UUID.randomUUID();
        UUID contentRefA = UUID.randomUUID();
        UUID contentRefB = UUID.randomUUID();
        UUID versionA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();

        String textA = "The " + TERM + " policy governs European logistics.";
        String textB = "Second document about " + TERM + " exceptions.";

        when(cache.listManifest(tenant, 200_000))
                .thenReturn(List.of(manifest(tenant, contentRefA, "a.md")))
                .thenReturn(List.of(manifest(tenant, contentRefA, "a.md"),
                        manifest(tenant, contentRefB, "b.md")));
        when(cache.readChunks(tenant, contentRefA))
                .thenReturn(List.of(chunk(tenant, contentRefA, textA, Set.of(versionA))));
        when(cache.readChunks(tenant, contentRefB))
                .thenReturn(List.of(chunk(tenant, contentRefB, textB, Set.of(versionB))));

        backend.project(tenant);
        assertThat(backend.candidates(tenant, TERM, 10)).hasSize(1);

        backend.project(tenant);
        var both = backend.candidates(tenant, TERM, 10);
        assertThat(both).hasSize(2);
        for (var candidate : both) {
            assertThat(candidate.sourceVersionIds()).hasSize(1);
            assertThat(candidate.provenanceRef()).startsWith("/v1/knowledge/");
        }
    }

    @Test
    void crossTenantCandidateGenerationIsDenied() throws Exception {
        String tenantA = "tenant-cross-a-" + UUID.randomUUID();
        String tenantB = "tenant-cross-b-" + UUID.randomUUID();
        UUID contentRefA = UUID.randomUUID();
        UUID versionA = UUID.randomUUID();

        when(cache.listManifest(tenantA, 200_000))
                .thenReturn(List.of(manifest(tenantA, contentRefA, "a.md")));
        when(cache.readChunks(tenantA, contentRefA))
                .thenReturn(List.of(chunk(tenantA, contentRefA,
                        "The " + TERM + " policy governs European logistics.", Set.of(versionA))));
        when(cache.listManifest(tenantB, 200_000)).thenReturn(List.of());

        backend.project(tenantA);
        assertThat(backend.candidates(tenantA, TERM, 10)).isNotEmpty();

        // Tenant B has no projection: candidate generation yields nothing and can
        // never return tenant A's chunks (per-tenant indexes, tenant-scoped reads).
        assertThatThrownBy(() -> backend.candidates(tenantB, TERM, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(tenantB);
    }
}

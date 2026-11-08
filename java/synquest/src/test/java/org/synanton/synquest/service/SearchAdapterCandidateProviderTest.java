package org.synanton.synquest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.common.resolution.CanonicalEntityLookup;
import org.synanton.common.resolution.ResolutionCandidate;
import org.synanton.common.resolution.ResolutionContext;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synquest.config.SynquestProperties;
import org.synanton.synquest.engine.lucene.LuceneIndexBuilder;
import org.synanton.synquest.resolution.SearchAdapterCandidateProvider;
import org.synanton.synquest.search.EntitySearchDocument;

class SearchAdapterCandidateProviderTest {

    private static final String TENANT = "tenant-provider";
    private static final String TENANT_B = "tenant-provider-b";
    private static final String MODEL = "test-model";

    @TempDir Path indexRoot;
    private IngestionCacheClient cache;
    private SynquestProperties props;
    private LuceneIndexBuilder builder;
    private QueryEmbedder embedder;
    private SearchService service;
    private CanonicalEntityLookup lookup;
    private SearchAdapterCandidateProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        cache = mock(IngestionCacheClient.class);
        CacheCursorStubs.wire(cache);
        when(cache.listManifest(eq(TENANT), anyInt())).thenReturn(List.of());
        when(cache.listManifest(eq(TENANT_B), anyInt())).thenReturn(List.of());
        when(cache.readEmbedding(eq(TENANT), any(), anyInt(), anyString())).thenReturn(Optional.empty());
        props = new SynquestProperties(
                new SynquestProperties.Index(indexRoot.toString(), false, 30), null,
                new SynquestProperties.Embedding(MODEL, 4, true, 0), null);
        builder = new LuceneIndexBuilder(cache, props);
        embedder = mock(QueryEmbedder.class);
        when(embedder.required()).thenReturn(false);
        service = new SearchService(builder, embedder, props);
        builder.build(TENANT);
        service.initTenant(TENANT);
        builder.build(TENANT_B);
        service.initTenant(TENANT_B);
        lookup = mock(CanonicalEntityLookup.class);
        provider = new SearchAdapterCandidateProvider(service, lookup);
    }

    @Test
    void exactEntityMatchIsVerifiedAgainstTheGraph() throws Exception {
        when(lookup.getActiveEntityType(TENANT, "e-halcyon")).thenReturn(Optional.of("MaintenanceSystem"));
        service.upsertEntities(TENANT, List.of(new EntitySearchDocument(
                "e-halcyon", "Halcyon-7", List.of(), "MaintenanceSystem", "coolant loop", null)));

        List<ResolutionCandidate> candidates = provider.candidates(context("Halcyon-7"));

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.entityId()).isEqualTo("e-halcyon");
            assertThat(candidate.canonicalType()).isEqualTo("MaintenanceSystem");
            assertThat(candidate.matchedField()).isEqualTo("label");
            assertThat(candidate.retrievalStrategy()).isEqualTo("RRF");
            assertThat(candidate.retrievalScore()).isPositive();
        });
    }

    @Test
    void aliasMatchIsReturned() throws Exception {
        when(lookup.getActiveEntityType(TENANT, "e-halcyon")).thenReturn(Optional.of("MaintenanceSystem"));
        service.upsertEntities(TENANT, List.of(new EntitySearchDocument(
                "e-halcyon", "Halcyon-7", List.of("the platform"), "MaintenanceSystem", "coolant loop", null)));

        List<ResolutionCandidate> candidates = provider.candidates(context("the platform"));

        assertThat(candidates).singleElement()
                .satisfies(candidate -> assertThat(candidate.matchedField()).isEqualTo("alias"));
    }

    @Test
    void chunkDocumentsAreNeverCandidates() throws Exception {
        UUID ref = UUID.randomUUID();
        when(cache.listManifest(eq(TENANT), anyInt()))
                .thenReturn(List.of(manifest(ref)));
        when(cache.readChunks(TENANT, ref)).thenReturn(List.of(
                new ChunkRow(TENANT, ref, 0, "Halcyon-7 is in this chunk", "sha0")));
        service.reindex(TENANT);

        assertThat(provider.candidates(context("Halcyon-7"))).isEmpty();
    }

    @Test
    void tenantIsolationNeverLeaksCandidates() throws Exception {
        when(lookup.getActiveEntityType(TENANT, "e-halcyon")).thenReturn(Optional.of("MaintenanceSystem"));
        service.upsertEntities(TENANT, List.of(new EntitySearchDocument(
                "e-halcyon", "Halcyon-7", List.of(), "MaintenanceSystem", "coolant loop", null)));

        assertThat(provider.candidates(new ResolutionContext(TENANT_B, "v1", "m1",
                "Halcyon-7", "MaintenanceSystem", List.of(), "1.0.0"))).isEmpty();
    }

    @Test
    void staleProjectionIsDropped() throws Exception {
        when(lookup.getActiveEntityType(TENANT, "e-halcyon")).thenReturn(Optional.empty());
        service.upsertEntities(TENANT, List.of(new EntitySearchDocument(
                "e-halcyon", "Halcyon-7", List.of(), "MaintenanceSystem", "coolant loop", null)));

        assertThat(provider.candidates(context("Halcyon-7"))).isEmpty();
    }

    @Test
    void bothLegsFuseToOneCandidate() throws Exception {
        float[] vector = new float[]{1, 0, 0, 0};
        when(embedder.embedForSearch("Halcyon-7", TENANT))
                .thenReturn(new QueryEmbedder.QueryVector(vector, false));
        when(lookup.getActiveEntityType(TENANT, "e-halcyon")).thenReturn(Optional.of("MaintenanceSystem"));
        service.upsertEntities(TENANT, List.of(new EntitySearchDocument(
                "e-halcyon", "Halcyon-7", List.of(), "MaintenanceSystem", "coolant loop", vector)));

        assertThat(provider.candidates(context("Halcyon-7"))).hasSize(1);
    }

    @Test
    void lookupInfrastructureFailureIsRetryable() throws Exception {
        when(lookup.getActiveEntityType(TENANT, "e-halcyon"))
                .thenThrow(new IllegalStateException("neo4j down"));
        service.upsertEntities(TENANT, List.of(new EntitySearchDocument(
                "e-halcyon", "Halcyon-7", List.of(), "MaintenanceSystem", "coolant loop", null)));

        assertThatThrownBy(() -> provider.candidates(context("Halcyon-7")))
                .isInstanceOf(RetryableProcessingException.class);
    }

    private static ResolutionContext context(String mention) {
        return new ResolutionContext(TENANT, "v1", "m1", mention, "MaintenanceSystem",
                List.of(), "1.0.0");
    }

    private static ManifestRow manifest(UUID ref) {
        return new ManifestRow(TENANT, ref, Instant.now(), 1, "fixed-window", 1, "CHUNKED",
                "CONTENT_CACHE", "", "file:///" + ref, "sha", 10, "text/plain", "FULL",
                null, "", null);
    }
}

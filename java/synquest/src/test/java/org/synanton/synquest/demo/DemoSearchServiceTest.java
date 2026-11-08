package org.synanton.synquest.demo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synquest.engine.lucene.EmbeddingShape;
import org.synanton.synquest.service.KnowledgeSearchBackend.KnowledgeCandidate;
import org.synanton.synquest.service.QueryEmbedder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoSearchServiceTest {

    private static final String QUESTION = "Which medications are listed for substance use disorders?";
    private static final String MODEL = "bge-base-en-v1.5";
    private static final UUID REF = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String CHUNK = REF + "#0";

    @TempDir Path temp;

    @Test
    void twoLegContributionsAreDirectValuesAndSum() throws Exception {
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", QUESTION, 20)).thenReturn(List.of(candidate()));
        var service = service(backend, cache(), clientWith(REF, List.of(embedding(REF, 0))), embedder(null));
        var result = service.search("tenant", QUESTION).results().get(0);
        assertThat(result.bm25Rank()).isEqualTo(1);
        assertThat(result.denseRank()).isEqualTo(1);
        assertThat(result.bm25Contribution()).isEqualTo(1.0 / 21);
        assertThat(result.denseContribution()).isEqualTo(1.0 / 21);
        assertThat(result.finalScore()).isEqualTo(2.0 / 21);
        assertThat(result.legsPresent()).containsExactly("bm25", "dense");
    }

    @Test
    void denseMissHasNullRankAndExactlyZeroContribution() throws Exception {
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", QUESTION, 20)).thenReturn(List.of(candidate()));
        UUID other = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var service = service(backend, cache(), clientWith(other, List.of(embedding(other, 0))), embedder(null));
        var result = service.search("tenant", QUESTION).results().stream()
                .filter(r -> r.chunkId().equals(CHUNK)).findFirst().orElseThrow();
        assertThat(result.denseRank()).isNull();
        assertThat(Double.compare(result.denseContribution(), 0.0)).isZero();
        assertThat(result.legsPresent()).containsExactly("bm25");
    }

    @Test
    void bm25MissIsDenseOnlyWithZeroBm25Contribution() throws Exception {
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", QUESTION, 20)).thenReturn(List.of());
        var cacheClient = clientWith(REF, List.of(embedding(REF, 0)));
        when(cacheClient.readChunks("tenant", REF)).thenReturn(List.of(chunk()));
        when(cacheClient.readManifest("tenant", REF)).thenReturn(Optional.of(manifest(REF)));
        var service = service(backend, cache(), cacheClient, embedder(null));
        var result = service.search("tenant", QUESTION).results().get(0);
        assertThat(result.bm25Rank()).isNull();
        assertThat(Double.compare(result.bm25Contribution(), 0.0)).isZero();
        assertThat(result.legsPresent()).containsExactly("dense");
    }

    @Test
    void sanitizedApostropheQueryUsesCacheWhileBm25SeesOriginalForm() throws Exception {
        String raw = "How does the report's glossary define a major depressive episode?";
        String sanitized = "How does the report&#39;s glossary define a major depressive episode?";
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", sanitized, 20)).thenReturn(List.of(candidate()));
        Path cache = temp.resolve("apostrophe.json");
        Files.writeString(cache, "{\"queries\":[{\"query_id\":\"q1\",\"embedding\":[1.0,0.0]}]}");
        Files.writeString(temp.resolve("queries-apostrophe.jsonl"),
                "{\"query_id\":\"q1\",\"question\":\"" + raw + "\"}\n");
        var service = new DemoSearchService(backend, mock(IngestionCacheClient.class), embedder(null),
                shape(), MODEL, cache, temp.resolve("queries-apostrophe.jsonl"));
        assertThat(service.search("tenant", sanitized).results()).isNotEmpty();
    }

    @Test
    void rawJsonIncludesNullBm25ForDenseOnly() throws Exception {
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", QUESTION, 20)).thenReturn(List.of());
        var client = clientWith(REF, List.of(embedding(REF, 0)));
        when(client.readChunks("tenant", REF)).thenReturn(List.of(chunk()));
        when(client.readManifest("tenant", REF)).thenReturn(Optional.of(manifest(REF)));
        var service = service(backend, cache(), client, embedder(null));
        var result = service.search("tenant", QUESTION).results().get(0);
        var mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String json = mapper.writeValueAsString(result);
        assertThat(json).contains("\"bm25_rank\":null").contains("\"bm25_contribution\":0.0");
    }

    @Test
    void denseOnlyTieUsesChunkIdAscending() {
        var a = result("a", 0.04, null, 0.0, 5, 0.04);
        var b = result("b", 0.04, null, 0.0, 5, 0.04);
        var ordered = new java.util.ArrayList<>(List.of(b, a));
        ordered.sort(DemoSearchService.ORDER);
        assertThat(ordered).extracting(DemoSearchService.DemoResult::chunkId).containsExactly("a", "b");
    }

    @Test
    void rawJsonIncludesNullRanksAndZeroContribution() throws Exception {
        var mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String json = mapper.writeValueAsString(result(CHUNK, 0.047619, 1, 0.047619, null, 0.0));
        assertThat(json).contains("\"dense_rank\":null");
        assertThat(json).contains("\"dense_contribution\":0.0");
    }

    @Test
    void emptyResultsAreSuccessful() throws Exception {
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", QUESTION, 20)).thenReturn(List.of());
        var service = service(backend, cache(), mock(IngestionCacheClient.class), embedder(null));
        assertThat(service.search("tenant", QUESTION).results()).isEmpty();
    }

    @Test
    void uncachedQueryWithoutCanonicalVectorsFallsBackToBm25Only() throws Exception {
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", "totally uncached question", 20)).thenReturn(List.of(candidate()));
        var service = service(backend, cache(), mock(IngestionCacheClient.class),
                embedder(new float[] {1.0f, 0.0f}));
        var result = service.search("tenant", "totally uncached question").results().get(0);
        assertThat(result.bm25Rank()).isEqualTo(1);
        assertThat(result.denseRank()).isNull();
        assertThat(result.legsPresent()).containsExactly("bm25");
    }

    @Test
    void uncachedQueryUsesLiveEmbeddingWhenCanonicalVectorsExist() throws Exception {
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", "totally uncached question", 20)).thenReturn(List.of(candidate()));
        var service = service(backend, cache(), clientWith(REF, List.of(embedding(REF, 0))),
                embedder(new float[] {1.0f, 0.0f}));
        var result = service.search("tenant", "totally uncached question").results().get(0);
        assertThat(result.denseRank()).isEqualTo(1);
        assertThat(result.legsPresent()).containsExactly("bm25", "dense");
    }

    @Test
    void failingQueryEmbeddingDegradesToBm25Only() throws Exception {
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", "totally uncached question", 20)).thenReturn(List.of(candidate()));
        QueryEmbedder failing = mock(QueryEmbedder.class);
        when(failing.embed(anyString(), any())).thenThrow(new IllegalStateException("tei down"));
        var service = service(backend, cache(), clientWith(REF, List.of(embedding(REF, 0))), failing);
        var result = service.search("tenant", "totally uncached question").results().get(0);
        assertThat(result.denseRank()).isNull();
        assertThat(result.legsPresent()).containsExactly("bm25");
    }

    @Test
    void queryTypeFallbackIsMixed() {
        assertThat(DemoSearchService.queryType("test")).isEqualTo("mixed");
        assertThat(DemoSearchService.queryType("IATF 16949")).isEqualTo("exact");
        assertThat(DemoSearchService.queryType("How does it work?")).isEqualTo("conceptual");
    }

    private DemoSearchService service(org.synanton.synquest.service.KnowledgeSearchBackend backend,
                                      Path cache, IngestionCacheClient client, QueryEmbedder embedder) {
        return new DemoSearchService(backend, client, embedder, shape(), MODEL, cache,
                temp.resolve("queries.jsonl"));
    }

    private static EmbeddingShape shape() {
        return new EmbeddingShape(2, 0);
    }

    private static QueryEmbedder embedder(float[] vector) {
        QueryEmbedder embedder = mock(QueryEmbedder.class);
        if (vector != null) {
            when(embedder.embed(anyString(), any())).thenReturn(vector);
        }
        return embedder;
    }

    private static IngestionCacheClient clientWith(UUID ref, List<EmbeddingRow> embeddings) {
        var client = mock(IngestionCacheClient.class);
        when(client.listManifest(eq("tenant"), anyInt())).thenReturn(List.of(manifest(ref)));
        when(client.listEmbeddings("tenant", ref)).thenReturn(embeddings);
        return client;
    }

    private static EmbeddingRow embedding(UUID ref, int ordinal) {
        return new EmbeddingRow("tenant", ref, ordinal, MODEL, "sha", new float[] {1.0f, 0.0f}, 2, Instant.now());
    }

    private Path cache() throws Exception {
        Path cache = temp.resolve("dense.json");
        Files.writeString(cache, "{\"queries\":[{\"query_id\":\"q1\",\"embedding\":[1.0,0.0]}]}");
        Files.writeString(temp.resolve("queries.jsonl"),
                "{\"query_id\":\"q1\",\"question\":\"" + QUESTION + "\"}\n");
        return cache;
    }

    private static KnowledgeCandidate candidate() {
        return new KnowledgeCandidate(REF + ":0", REF, 0, 1.0, "title", "snippet", "file:///doc",
                Set.of(UUID.randomUUID()), 1, 2, "section", List.of("section"));
    }

    private static ChunkRow chunk() {
        return new ChunkRow("tenant", REF, 0, "text", "sha", 1, 2, "section", "section", "heading");
    }

    private static ManifestRow manifest(UUID ref) {
        return new ManifestRow("tenant", ref, Instant.now(), 1, "semantic-v1", 1, "READY", "hot",
                "local", "file:///doc", "sha", 10, "text/plain", "high", null, "{}", "cache");
    }

    private static DemoSearchService.DemoResult result(String chunk, double finalScore, Integer bm25Rank,
                                                       double bm25Contribution, Integer denseRank,
                                                       double denseContribution) {
        return new DemoSearchService.DemoResult(1, finalScore, bm25Rank, bm25Contribution, denseRank,
                denseContribution, bm25Rank == null ? List.of("dense") : denseRank == null ? List.of("bm25")
                : List.of("bm25", "dense"), chunk, "title", "snippet",
                new DemoSearchService.Provenance("file:///doc", List.of(), "/v1/knowledge/x", null, null, null));
    }
}

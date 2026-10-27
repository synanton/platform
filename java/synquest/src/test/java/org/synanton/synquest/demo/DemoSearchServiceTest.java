package org.synanton.synquest.demo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synquest.service.KnowledgeSearchBackend.KnowledgeCandidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoSearchServiceTest {

    private static final String QUESTION = "Which medications are listed for substance use disorders?";
    private static final UUID REF = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String CHUNK = REF + "#0";

    @TempDir Path temp;

    @Test
    void twoLegContributionsAreDirectValuesAndSum() throws Exception {
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", QUESTION, 20)).thenReturn(List.of(candidate()));
        var service = service(backend, cache(List.of(CHUNK)), mock(IngestionCacheClient.class));
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
        var other = UUID.fromString("22222222-2222-4222-8222-222222222222") + "#0";
        var service = service(backend, cache(List.of(other)), mock(IngestionCacheClient.class));
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
        var cacheClient = mock(IngestionCacheClient.class);
        when(cacheClient.readChunks("tenant", REF)).thenReturn(List.of(chunk()));
        when(cacheClient.readManifest("tenant", REF)).thenReturn(Optional.of(manifest()));
        var service = service(backend, cache(List.of(CHUNK)), cacheClient);
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
        Files.writeString(cache, "{\"chunks\":[{\"id\":\"" + CHUNK + "\",\"embedding\":[1.0,0.0]}],\"queries\":[{\"query_id\":\"q1\",\"embedding\":[1.0,0.0]}]}");
        Files.writeString(temp.resolve("queries.jsonl"),
                "{\"query_id\":\"q1\",\"question\":\"" + raw + "\"}\n");
        var service = new DemoSearchService(backend, mock(IngestionCacheClient.class), cache, temp.resolve("queries.jsonl"));
        assertThat(service.search("tenant", sanitized).results()).isNotEmpty();
    }

    @Test
    void rawJsonIncludesNullBm25ForDenseOnly() throws Exception {
        var backend = mock(org.synanton.synquest.service.KnowledgeSearchBackend.class);
        when(backend.candidates("tenant", QUESTION, 20)).thenReturn(List.of());
        var client = mock(IngestionCacheClient.class);
        when(client.readChunks("tenant", REF)).thenReturn(List.of(chunk()));
        when(client.readManifest("tenant", REF)).thenReturn(Optional.of(manifest()));
        var service = service(backend, cache(List.of(CHUNK)), client);
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
        var service = service(backend, cache(List.of()), mock(IngestionCacheClient.class));
        assertThat(service.search("tenant", QUESTION).results()).isEmpty();
    }

    @Test
    void queryTypeFallbackIsMixed() {
        assertThat(DemoSearchService.queryType("test")).isEqualTo("mixed");
        assertThat(DemoSearchService.queryType("IATF 16949")).isEqualTo("exact");
        assertThat(DemoSearchService.queryType("How does it work?")).isEqualTo("conceptual");
    }

    private DemoSearchService service(org.synanton.synquest.service.KnowledgeSearchBackend backend,
                                      Path cache, IngestionCacheClient client) throws Exception {
        return new DemoSearchService(backend, client, cache, temp.resolve("queries.jsonl"));
    }

    private Path cache(List<String> chunkIds) throws Exception {
        String chunks = String.join(",", chunkIds.stream()
                .map(id -> "{\"id\":\"" + id + "\",\"embedding\":[1.0,0.0]}").toList());
        Path cache = temp.resolve("dense.json");
        Files.writeString(cache, "{\"chunks\":[" + chunks + "],\"queries\":[{\"query_id\":\"q1\",\"embedding\":[1.0,0.0]}]}");
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

    private static ManifestRow manifest() {
        return new ManifestRow("tenant", REF, Instant.now(), 1, "semantic-v1", 1, "READY", "hot",
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

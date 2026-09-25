package org.synanton.synquest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.LlmClient;
import org.synanton.synquest.api.dto.Hit;
import org.synanton.synquest.api.dto.SearchRequest;
import org.synanton.synquest.config.SynquestProperties;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B2/T05 small-to-big: expand=section pulls a hit's whole section, in reading order. */
class SearchServiceHierarchyTest {

    @TempDir Path indexRoot;
    private final String tenant = "t";
    private final UUID ref = UUID.randomUUID();
    private SearchService service;
    private LuceneIndexBuilder builder;

    /** (ordinal, text, sectionId, parent, level): mirrors pdf005: heading chunk split from its table. */
    private static final Object[][] CHUNKS = {
            {0, "Appendix C medication lists overview", "s3", "", 1},
            {1, "Medications for Substance Use Disorders", "s4", "s3", 2},
            {2, "Acamprosate | Disulfiram | Naltrexone", "s4", "s3", 2},
            {3, "Buprenorphine | Methadone", "s4", "s3", 2},
            {4, "flat fallback chunk about something else", "", "", 0},
    };

    @BeforeEach
    void index() throws Exception {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        when(cache.listManifest(eq(tenant), anyInt())).thenReturn(List.of(new ManifestRow(tenant, ref, Instant.now(), 1,
                "structure-aware", 1, "EMBEDDED", "hot", null, "file:///mh.pdf", "sha", 10, "application/pdf", null, null, null)));
        List<ChunkRow> rows = new ArrayList<>();
        for (Object[] c : CHUNKS) {
            int ord = (int) c[0];
            rows.add(new ChunkRow(tenant, ref, ord, (String) c[1], "s" + ord, -1, -1, "", "TABLE", "", "[]", 5, "", false,
                    ChunkRow.PUBLIC_ONLY, (String) c[2], (String) c[3], (int) c[4]));
            when(cache.readEmbedding(tenant, ref, ord, "m"))
                    .thenReturn(Optional.of(new EmbeddingRow(tenant, ref, ord, "m", "s" + ord, new float[]{1f, ord}, 2, Instant.now())));
        }
        when(cache.readChunks(tenant, ref)).thenReturn(rows);
        var props = new SynquestProperties(new SynquestProperties.Index(indexRoot.toString(), true, 30), null,
                new SynquestProperties.Embedding("m", 2, true, 0));
        var shape = new EmbeddingShape(props);
        builder = new LuceneIndexBuilder(cache, props, shape, true);
        LlmClient emb = new LlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r) { return new EmbedResponse(List.of(new float[]{1f, 0f})); }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
        service = new SearchService(builder, new QueryEmbedder(emb, props, true, shape, new QueryEmbeddingCache(0)), props);
        service.initTenant(tenant);
    }

    private static SearchRequest req(int topK, String expand, Integer max) {
        // lexical-only ranking (top_k_dense 0) keeps the test deterministic
        return new SearchRequest("t", "substance use disorders", topK, 0, 100, 60, null, null, expand, max);
    }

    @Test
    void buildReportCountsHierarchyChunks() {
        assertThat(builder.lastReport(tenant).orElseThrow().sectionDocs()).isEqualTo(4);
    }

    @Test
    void headingHitPullsInItsTableSiblingsInReadingOrder() throws Exception {
        List<Hit> plain = service.search(req(3, null, null)).hits();
        assertThat(plain.get(0).chunkOrdinal()).isEqualTo(1);           // only the heading chunk matches
        assertThat(plain).noneMatch(h -> h.expandedFrom() != null);

        List<Hit> expanded = service.search(req(3, "section", null)).hits();
        assertThat(expanded).extracting(Hit::chunkOrdinal).containsExactly(1, 2, 3);
        assertThat(expanded.get(0).expandedFrom()).isNull();              // the hit itself
        assertThat(expanded.get(1).expandedFrom()).isEqualTo(ref + "#1");
        assertThat(expanded).allMatch(h -> "s4".equals(h.sectionId()));
        assertThat(expanded.get(2).score()).isEqualTo(expanded.get(0).score()); // inherited
    }

    @Test
    void expansionIsCappedPerSectionAndRespectsTopK() throws Exception {
        assertThat(service.search(req(2, "section", null)).hits()).extracting(Hit::chunkOrdinal).containsExactly(1, 2);
        List<Hit> capped = service.search(req(10, "section", 2)).hits();
        assertThat(capped.stream().filter(h -> "s4".equals(h.sectionId())).count()).isEqualTo(2);
    }
}

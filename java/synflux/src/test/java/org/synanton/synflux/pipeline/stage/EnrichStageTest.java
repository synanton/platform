package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.AnalysisRow;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.LlmClient;
import org.synanton.synflux.domain.*;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EnrichStageTest {

    private LlmClient fakeLlm;
    private IngestionCacheClient fakeCache;
    private EnrichStage stage;

    @BeforeEach
    void setUp() {
        fakeLlm = mock(LlmClient.class);
        fakeCache = mock(IngestionCacheClient.class);
        stage = new EnrichStage(fakeLlm, fakeCache, "test-model", 2);
    }

    private ChunkedDocument makeDoc(String... chunkTexts) {
        var ref = new ContentRef("file", "file:///test.txt", "text/plain", 100, Instant.now());
        var acquired = new AcquiredDocument(ref, new byte[0], "sha256test", "text/plain", "file:///test.txt", UUID.randomUUID());
        var parsed = new ParsedDocument(acquired, String.join(" ", chunkTexts), Map.of(), null);
        List<SemanticChunk> chunks = new ArrayList<>();
        for (int i = 0; i < chunkTexts.length; i++) {
            chunks.add(new SemanticChunk(
                "doc-c" + i, "doc", i, SemanticChunk.ChunkType.FALLBACK,
                chunkTexts[i], null, List.of(), null, List.of(),
                -1, -1, 10, false, Map.of(), "sha" + i));
        }
        return new ChunkedDocument(parsed, chunks);
    }

    @Test
    void enrichesDocumentWithLlmCalls() {
        String pass1Json = "{\"summary\": \"test summary\", \"entity_strings\": [\"Acme Corp\"]}";
        String pass2Json = "{\"typed_entities\": [{\"label\": \"Acme Corp\", \"type\": \"ORGANIZATION\", \"confidence\": 0.9}], \"relations\": []}";

        when(fakeLlm.complete(any(CompletionRequest.class)))
            .thenReturn(new CompletionResponse(pass1Json, 10, 50))
            .thenReturn(new CompletionResponse(pass2Json, 50, 100));
        when(fakeCache.readAnalysisByInputHash(any(), any())).thenReturn(Optional.empty());

        var doc = makeDoc("Acme Corp supplies widgets to Globex.");
        var ctx = new StageContext("demo", UUID.randomUUID().toString(), null);
        var result = stage.apply(doc, ctx);

        assertThat(result).isSameAs(doc);
        verify(fakeCache, times(2)).upsertAnalysis(any(AnalysisRow.class));
    }

    @Test
    void cacheHitSkipsLlmCall() {
        var cachedRow = new AnalysisRow("demo", UUID.randomUUID(), 0, 1, "test-model", "v1.0",
            "{\"summary\":\"cached\",\"entity_strings\":[]}", "somehash", Instant.now());
        when(fakeCache.readAnalysisByInputHash(any(), any())).thenReturn(Optional.of(cachedRow));

        var doc = makeDoc("Some text about supply chains.");
        var ctx = new StageContext("demo", UUID.randomUUID().toString(), null);
        stage.apply(doc, ctx);

        // Pass 1 cached → no LLM call for it; Pass 2 also cached since combined summary matches
        verify(fakeLlm, never()).complete(any());
    }

    @Test
    void passOneFailureDoesNotAbortJob() {
        when(fakeLlm.complete(any(CompletionRequest.class))).thenThrow(new RuntimeException("LLM down"));
        when(fakeCache.readAnalysisByInputHash(any(), any())).thenReturn(Optional.empty());

        var doc = makeDoc("Some text.");
        var ctx = new StageContext("demo", UUID.randomUUID().toString(), null);
        // Should not throw
        var result = stage.apply(doc, ctx);
        assertThat(result).isSameAs(doc);
    }
}

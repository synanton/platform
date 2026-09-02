package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.AnnotationRow;
import org.synanton.synflux.annotation.AnnotationRule;
import org.synanton.synflux.annotation.AnnotationsServiceClient;
import org.synanton.synflux.annotation.KeywordAnnotationRule;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AnnotationStageTest {

    private IngestionCacheClient fakeCache;
    private AnnotationsServiceClient fakeAnnotationsClient;
    private UUID processingRunId;

    @BeforeEach
    void setUp() {
        fakeCache = mock(IngestionCacheClient.class);
        fakeAnnotationsClient = mock(AnnotationsServiceClient.class);
        processingRunId = UUID.randomUUID();
        when(fakeAnnotationsClient.startProcessingRun(any(), any(), any(), any(), any(), any()))
                .thenReturn(processingRunId);
    }

    private ChunkedDocument makeDoc(String... chunkTexts) {
        UUID refId = UUID.randomUUID();
        var ref = new ContentRef("file", "file:///test.txt", "text/plain", 100, Instant.now());
        var acquired = new AcquiredDocument(ref, new byte[0], "sha256test", "text/plain", "file:///test.txt", refId);
        var parsed = new ParsedDocument(acquired, "text", Map.of(), null);
        List<SemanticChunk> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < chunkTexts.length; i++) {
            chunks.add(new SemanticChunk(
                    "c" + i, refId.toString(), i, SemanticChunk.ChunkType.PARAGRAPH,
                    chunkTexts[i], null, List.of(), null, List.of(),
                    -1, -1, 10, false, Map.of(), SemanticChunk.PUBLIC_ONLY, "sha" + i));
        }
        return new ChunkedDocument(parsed, chunks);
    }

    private List<AnnotationRule> paymentRule() {
        return List.of(new KeywordAnnotationRule(
                "payment-detection", 4, "TAG", "billing", "payment",
                "payment-rule-engine", "4.2", List.of("invoice number")));
    }

    @Test
    void shouldWriteAnnotationForMatchingChunk() {
        AnnotationStage stage = new AnnotationStage(fakeCache, fakeAnnotationsClient, paymentRule(), "rule-engine", "1.0");
        ChunkedDocument doc = makeDoc("please reference the invoice number on file", "unrelated text");

        stage.apply(doc, new StageContext("demo", "job", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AnnotationRow>> captor = (ArgumentCaptor<List<AnnotationRow>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(fakeCache).insertAnnotations(captor.capture());
        List<AnnotationRow> rows = captor.getValue();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().definitionId()).isEqualTo("payment-detection");
        assertThat(rows.getFirst().processingRunId()).isEqualTo(processingRunId);
        assertThat(rows.getFirst().sourceClassification()).containsExactly("PUBLIC");
    }

    @Test
    void shouldNotWriteWhenNothingMatches() {
        AnnotationStage stage = new AnnotationStage(fakeCache, fakeAnnotationsClient, paymentRule(), "rule-engine", "1.0");
        ChunkedDocument doc = makeDoc("nothing relevant here");

        stage.apply(doc, new StageContext("demo", "job", null));

        verify(fakeCache, never()).insertAnnotations(anyList());
    }

    @Test
    void shouldStartAndCompleteAProcessingRunEvenWithNoMatches() {
        AnnotationStage stage = new AnnotationStage(fakeCache, fakeAnnotationsClient, paymentRule(), "rule-engine", "1.0");
        ChunkedDocument doc = makeDoc("nothing relevant here");

        stage.apply(doc, new StageContext("demo", "job", null));

        verify(fakeAnnotationsClient).startProcessingRun(
                eq("rule-engine"), eq("1.0"), eq("demo"), any(), any(), any());
        verify(fakeAnnotationsClient).completeProcessingRun(eq(processingRunId), eq("SUCCEEDED"), any());
    }

    @Test
    void shouldMarkProcessingRunFailedWhenCacheWriteThrows() {
        doThrow(new RuntimeException("cassandra unavailable")).when(fakeCache).insertAnnotations(anyList());
        AnnotationStage stage = new AnnotationStage(fakeCache, fakeAnnotationsClient, paymentRule(), "rule-engine", "1.0");
        ChunkedDocument doc = makeDoc("please reference the invoice number on file");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> stage.apply(doc, new StageContext("demo", "job", null)));

        verify(fakeAnnotationsClient).completeProcessingRun(eq(processingRunId), eq("FAILED"), eq("cassandra unavailable"));
    }
}

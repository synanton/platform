package org.synanton.synflux.extraction.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ExtractionRequestRow;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.synanton.synflux.config.ExtractionGraphProperties;

class GraphWriterTest {

    private static final String TENANT = "tenant";
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID REF = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final RelationValidator validator = mock(RelationValidator.class);
    private final GraphWriteClient graphClient = mock(GraphWriteClient.class);

    @Test
    void writesDeterministicMentionAndRelationIdsWithDocumentOffsets() {
        when(validator.validate(any(), any(), any(), any(), any())).thenReturn(new RelationValidation(true, List.of()));
        when(graphClient.upsertMentions(any())).thenReturn(2);
        when(graphClient.upsertRelations(any())).thenReturn(1);
        when(cache.claimGraphCommit(any())).thenReturn(true);
        GraphWriter writer = writer(true);
        ExtractionRequestRow request = request();
        ChunkRow chunk = chunk();
        ExtractionResult result = result();

        writer.commit(request, List.of(chunk), Map.of(0, result));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GraphMention>> mentions = ArgumentCaptor.forClass(List.class);
        verify(graphClient).upsertMentions(mentions.capture());
        assertThat(mentions.getValue()).hasSize(2);
        GraphMention subject = mentions.getValue().getFirst();
        assertThat(subject.mentionId()).isEqualTo(StableIds.mentionId(
                TENANT, VERSION.toString(), REF + ":0", 0, 9, "Halcyon-7"));
        assertThat(subject.chunkOffsetStart()).isZero();
        assertThat(subject.documentOffsetStart()).isEqualTo(100);
        assertThat(subject.documentOffsetEnd()).isEqualTo(109);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GraphRelation>> relations = ArgumentCaptor.forClass(List.class);
        verify(graphClient).upsertRelations(relations.capture());
        assertThat(relations.getValue()).hasSize(1);
        assertThat(relations.getValue().getFirst().relationId()).isEqualTo(StableIds.relationId(
                TENANT, VERSION.toString(), subject.mentionId(), "requires",
                mentions.getValue().get(1).mentionId()));
        assertThat(relations.getValue().getFirst().evidenceDocumentOffsetStart()).isEqualTo(100);
        assertThat(relations.getValue().getFirst().evidenceChunkId()).isEqualTo(REF + ":0");
        assertThat(relations.getValue().getFirst().sourceId()).isEqualTo("source");
        assertThat(relations.getValue().getFirst().revisionId()).isEqualTo(VERSION.toString());
        assertThat(relations.getValue().getFirst().generation()).isEqualTo(7);

        ArgumentCaptor<Map<String, Integer>> counters = ArgumentCaptor.forClass(Map.class);
        verify(cache).claimGraphCommit(request);
        verify(cache).markGraphCommitted(eq(request), eq("GRAPH_COMMITTED"), counters.capture(), eq(0), any(), any(java.util.UUID.class), eq("ActivationRequested"), any(String.class));
        assertThat(counters.getValue()).containsEntry("mentions_accepted", 2)
                .containsEntry("relations_accepted", 1)
                .containsEntry("relations_rejected", 0);
    }

    @Test
    void commitsIdempotentResolutionWorkForEveryMention() {
        when(validator.validate(any(), any(), any(), any(), any())).thenReturn(new RelationValidation(true, List.of()));
        when(graphClient.upsertMentions(any())).thenReturn(2);
        when(graphClient.upsertRelations(any())).thenReturn(1);
        when(cache.claimGraphCommit(any())).thenReturn(true);
        ExtractionRequestRow request = request();

        writer(true).commit(request, List.of(chunk()), Map.of(0, result()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ResolutionWorkRow> work = ArgumentCaptor.forClass(ResolutionWorkRow.class);
        verify(cache, org.mockito.Mockito.times(2)).insertResolutionWork(work.capture());
        assertThat(work.getAllValues()).allSatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo(TENANT);
            assertThat(row.sourceVersionId()).isEqualTo(VERSION);
            assertThat(row.connectorId()).isEqualTo("upload");
            assertThat(row.sourceId()).isEqualTo("source");
            assertThat(row.resourceId()).isEqualTo("resource");
            assertThat(row.claimStatus()).isEqualTo("PENDING");
            assertThat(row.attemptCount()).isZero();
            assertThat(row.workCreatedAt()).isEqualTo(NOW);
        });
        assertThat(work.getAllValues().getFirst().chunkOrdinal()).isZero();
    }

    @Test
    void rejectedRelationProducesPartialStatusAndIsNotWritten() {
        when(validator.validate(any(), any(), any(), any(), any())).thenReturn(
                new RelationValidation(false, List.of(new RelationValidation.Violation("range_mismatch", "bad", "x"))));
        when(graphClient.upsertMentions(any())).thenReturn(2);
        when(cache.claimGraphCommit(any())).thenReturn(true);
        GraphWriter writer = writer(true);
        ExtractionRequestRow request = request();

        writer.commit(request, List.of(chunk()), Map.of(0, result()));

        verify(graphClient, never()).upsertRelations(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Integer>> counters = ArgumentCaptor.forClass(Map.class);
        verify(cache).markGraphCommitted(eq(request), eq("PARTIAL"), counters.capture(), eq(0), any(), isNull(), isNull(), isNull());
        assertThat(counters.getValue()).containsEntry("relations_rejected", 1);
    }

    @Test
    void validationFailureMarksGraphFailedWithoutWrites() {
        when(validator.validate(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("syntology down"));
        when(cache.claimGraphCommit(any())).thenReturn(true);
        GraphWriter writer = writer(true);
        ExtractionRequestRow request = request();

        writer.commit(request, List.of(chunk()), Map.of(0, result()));

        verify(graphClient, never()).upsertMentions(any());
        verify(cache).markGraphFailed(eq(request), eq("FAILED"), anyMap(), eq(1), any(), any());
    }

    @Test
    void persistedCountMismatchMarksGraphFailed() {
        when(validator.validate(any(), any(), any(), any(), any())).thenReturn(new RelationValidation(true, List.of()));
        when(graphClient.upsertMentions(any())).thenReturn(1);
        when(cache.claimGraphCommit(any())).thenReturn(true);
        ExtractionRequestRow request = request();

        writer(true).commit(request, List.of(chunk()), Map.of(0, result()));

        verify(graphClient, never()).upsertRelations(any());
        verify(cache).markGraphFailed(eq(request), eq("FAILED"), anyMap(), eq(1), any(), any());
    }

    @Test
    void disabledGraphWriteIsANoOp() {
        writer(false).commit(request(), List.of(chunk()), Map.of(0, result()));
        verify(cache, never()).claimGraphCommit(any());
    }

    private GraphWriter writer(boolean enabled) {
        return new GraphWriter(cache, validator, graphClient,
                new ExtractionGraphProperties(enabled, "http://syntology", "http://relix", "1.0.0"), CLOCK);
    }

    private static ExtractionRequestRow request() {
        return new ExtractionRequestRow(TENANT, "upload", "source", "resource", UUID.randomUUID(),
                VERSION, REF, "{}", NOW, NOW, "KNOWLEDGE_READY", 0, null, null, false, 7);
    }

    private static ChunkRow chunk() {
        return new ChunkRow(TENANT, REF, 0, "Halcyon-7 requires a helium leak check.", "sha",
                -1, -1, "", "", "", "[]", 0, "", false, ChunkRow.PUBLIC_ONLY,
                "", "", 0, null, null, 100, 141);
    }

    private static ExtractionResult result() {
        PreEntity subject = new PreEntity("m1", "Halcyon-7", "MaintenanceSystem", 0, 9, "ctx");
        PreEntity object = new PreEntity("m2", "helium leak check", "Procedure", 23, 40, "ctx");
        return new ExtractionResult(List.of(subject, object),
                List.of(new RelationCandidate("m1", "requires", "m2", "ref:0", 0, 41)));
    }
}

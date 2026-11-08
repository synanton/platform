package org.synanton.synflux.extraction.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.synanton.ingestioncache.domain.ExtractionResultRow;
import org.synanton.ingestioncache.domain.GraphCommitRequestRow;
import org.synanton.synflux.config.ExtractionConsumerProperties;
import org.synanton.synflux.config.ExtractionGraphProperties;

class GraphCommitConsumerTest {

    private static final String TENANT = "tenant";
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID REF = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final GraphWriter graphWriter = mock(GraphWriter.class);

    @Test
    void retriesAFailedGraphCommitFromPersistedResults() throws Exception {
        GraphCommitRequestRow row = new GraphCommitRequestRow(TENANT, "upload", "source", "resource",
                UUID.randomUUID(), VERSION, REF, "{}", NOW, NOW, "KNOWLEDGE_READY", "FAILED",
                2, NOW.plusSeconds(30), 0, null, null, false);
        when(cache.listPendingGraphCommits(eq(TENANT), anyInt(), any())).thenReturn(List.of(row));
        PreEntity entity = new PreEntity("m1", "Halcyon-7", "MaintenanceSystem", 0, 9, "ctx");
        RelationCandidate relation = new RelationCandidate("m1", "requires", "m1", "ref:0", 0, 9);
        when(cache.listExtractionResults(TENANT, REF)).thenReturn(List.of(new ExtractionResultRow(
                TENANT, REF, 0, VERSION, mapper.writeValueAsString(List.of(entity)),
                mapper.writeValueAsString(List.of(relation)), NOW)));
        when(cache.readChunks(TENANT, REF)).thenReturn(List.of(chunk()));

        consumer(true).poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Integer, ExtractionResult>> results = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<ExtractionRequestRow> request = ArgumentCaptor.forClass(ExtractionRequestRow.class);
        verify(graphWriter).commit(request.capture(), eq(List.of(chunk())), results.capture(), eq(2));
        assertThat(request.getValue().versionId()).isEqualTo(VERSION);
        assertThat(results.getValue()).containsKey(0);
        assertThat(results.getValue().get(0).preEntities()).hasSize(1);
    }

    @Test
    void disabledGraphWritesDoNotPoll() {
        consumer(false).poll();
        verify(cache, never()).listPendingGraphCommits(any(), anyInt(), any());
    }

    private GraphCommitConsumer consumer(boolean enabled) {
        return new GraphCommitConsumer(cache, graphWriter,
                new ExtractionConsumerProperties(true, TENANT, 1, 5, 30, 5000L),
                new ExtractionGraphProperties(enabled, "http://syntology", "http://relix", "1.0.0"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ChunkRow chunk() {
        return new ChunkRow(TENANT, REF, 0, "Halcyon-7 requires a helium leak check.", "sha",
                -1, -1, "", "", "", "[]", 0, "", false, ChunkRow.PUBLIC_ONLY,
                "", "", 0, null, null, 100, 141);
    }
}

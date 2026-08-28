package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ObjectStorePort;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistStageTest {

    @Test
    void shouldPersistSemanticProvenanceAndChunkedState() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        ObjectStorePort store = mock(ObjectStorePort.class);
        PersistStage stage = new PersistStage(cache, store, "synanton-hot");

        UUID refId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ContentRef contentRef = new ContentRef("file", "file:///tmp/a.md", "text/markdown", 10, Instant.now());
        AcquiredDocument acquired = new AcquiredDocument(contentRef, new byte[]{1}, "aa", "text/markdown",
                "file:///tmp/a.md", refId);
        ParsedDocument parsed = new ParsedDocument(acquired, "hello", Map.of(), null);
        SemanticChunk chunk = new SemanticChunk(
                "c0", refId.toString(), 0, SemanticChunk.ChunkType.SECTION, "hello", null,
                List.of("Supply chain", "Europe"), "Europe", List.of("e1"), 2, 2, 1, false, Map.of(),
                SemanticChunk.PUBLIC_ONLY, "bb");
        ChunkedDocument doc = new ChunkedDocument(parsed, List.of(chunk));

        SynfluxProperties props = new SynfluxProperties(
                new SynfluxProperties.Ingest(1, 1000, new SynfluxProperties.Ingest.Chunk("semantic-v1", 400, 50)),
                new SynfluxProperties.Pipeline(false, false),
                new SynfluxProperties.Enrichment("http://x", "m", 1),
                new SynfluxProperties.Embedding("http://x", "m", 1),
                new SynfluxProperties.Kafka(1, 1, 1)
        );
        stage.apply(doc, new StageContext("demo", "job", props));

        verify(cache).insertChunks(anyList());
    }
}

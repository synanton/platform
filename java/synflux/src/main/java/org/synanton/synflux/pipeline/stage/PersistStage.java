package org.synanton.synflux.pipeline.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ResourceUsage;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.port.ObjectStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public class PersistStage implements PipelineStage<ChunkedDocument, ChunkedDocument> {

    private static final Logger log = LoggerFactory.getLogger(PersistStage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IngestionCacheClient cacheClient;
    private final ObjectStorePort objectStore;
    private final String hotBucket;

    public PersistStage(IngestionCacheClient cacheClient, ObjectStorePort objectStore, String hotBucket) {
        this.cacheClient = cacheClient;
        this.objectStore = objectStore;
        this.hotBucket = hotBucket;
    }

    @Override
    public String name() { return "persist"; }

    @Override
    public ChunkedDocument apply(ChunkedDocument doc, StageContext ctx) {
        var acquired = doc.parsed().acquired();
        String key = ctx.tenant() + "/" + acquired.contentRefId();
        String archiveLocation = "s3://" + hotBucket + "/" + key;

        objectStore.putObject(
            hotBucket, key,
            new ByteArrayInputStream(acquired.bytes()),
            acquired.bytes().length,
            acquired.mimeType()
        );

        String ingestUsageJson = ResourceUsage.fromStages(ctx.usage().stages()).toJson();
        String state = ctx.props().pipeline().embeddingEnabled() ? "EMBEDDED" : "CHUNKED";
        cacheClient.upsertManifest(new ManifestRow(
            ctx.tenant(), acquired.contentRefId(), Instant.now(),
            1, "semantic-v1", 1, state, "HOT", archiveLocation,
            acquired.sourceUri(), acquired.sha256(), acquired.bytes().length,
            acquired.mimeType(), "FULL", null, ingestUsageJson
        ));

        List<ChunkRow> chunkRows = doc.chunks().stream()
            .map(c -> toChunkRow(ctx.tenant(), acquired.contentRefId(), c))
            .collect(Collectors.toList());
        if (!chunkRows.isEmpty()) {
            cacheClient.insertChunks(chunkRows);
        }

        log.info("Persisted {} chunks for ref={}", chunkRows.size(), acquired.contentRefId());
        return doc;
    }

    private static ChunkRow toChunkRow(String tenantId, java.util.UUID contentRefId, SemanticChunk c) {
        return new ChunkRow(
            tenantId, contentRefId, c.ordinal(), c.text(), c.sha256(),
            c.pageStart(), c.pageEnd(),
            c.sectionPath() == null ? "" : String.join(" > ", c.sectionPath()),
            c.type() == null ? "" : c.type().name(),
            c.heading() == null ? "" : c.heading(),
            toJson(c.sourceElements()),
            c.tokenCount(),
            toJson(c.structuredContent()),
            c.isPartialSection()
        );
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chunk field", e);
        }
    }
}

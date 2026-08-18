package org.synanton.synflux.pipeline.stage;

import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synflux.domain.ChunkedDocument;
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

        // Store raw bytes in MinIO
        objectStore.putObject(
            hotBucket, key,
            new ByteArrayInputStream(acquired.bytes()),
            acquired.bytes().length,
            acquired.mimeType()
        );

        // Upsert manifest
        cacheClient.upsertManifest(new ManifestRow(
            ctx.tenant(), acquired.contentRefId(), Instant.now(),
            1, "fixed-window", 1, "CHUNKED", "HOT", archiveLocation,
            acquired.sourceUri(), acquired.sha256(), acquired.bytes().length,
            acquired.mimeType(), "FULL", null
        ));

        // Persist chunks
        List<ChunkRow> chunkRows = doc.chunks().stream()
            .map(c -> new ChunkRow(ctx.tenant(), acquired.contentRefId(), c.ordinal(), c.text(), c.sha256()))
            .collect(Collectors.toList());
        if (!chunkRows.isEmpty()) {
            cacheClient.insertChunks(chunkRows);
        }

        log.info("Persisted {} chunks for ref={}", chunkRows.size(), acquired.contentRefId());
        return doc;
    }
}

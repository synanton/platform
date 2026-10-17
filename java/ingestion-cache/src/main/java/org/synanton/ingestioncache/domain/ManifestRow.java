package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

public record ManifestRow(
    String tenantId,
    UUID contentRefId,
    Instant ingestedAt,
    int schemaVersion,
    String chunkStrategy,
    int chunkStrategyVersion,
    String state,
    String storageTier,
    String archiveLocation,
    String sourceUri,
    String sourceSha256,
    long sizeBytes,
    String mimeType,
    String embeddingQuality,
    String enrichmentModelId,
    String ingestUsage,
    String contentCacheReference
) {
    public ManifestRow(
            String tenantId,
            UUID contentRefId,
            Instant ingestedAt,
            int schemaVersion,
            String chunkStrategy,
            int chunkStrategyVersion,
            String state,
            String storageTier,
            String archiveLocation,
            String sourceUri,
            String sourceSha256,
            long sizeBytes,
            String mimeType,
            String embeddingQuality,
            String enrichmentModelId,
            String ingestUsage) {
        this(tenantId, contentRefId, ingestedAt, schemaVersion, chunkStrategy, chunkStrategyVersion, state,
            storageTier, archiveLocation, sourceUri, sourceSha256, sizeBytes, mimeType, embeddingQuality,
            enrichmentModelId, ingestUsage, null);
    }
}

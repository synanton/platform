package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable source revision and the content-addressed artifact published for it. */
public record SourceVersionRow(
    String tenantId,
    String connectorId,
    String sourceId,
    String resourceId,
    UUID versionId,
    String contentDigest,
    String sourceRevision,
    Instant observedAt,
    UUID contentRefId,
    UUID incarnationId,
    String contentCacheReference,
    long generation,
    String contentHash,
    UUID previousRevisionId
) {
    /** Legacy-compatible constructor for callers that do not yet carry lifecycle generations. */
    public SourceVersionRow(
            String tenantId,
            String connectorId,
            String sourceId,
            String resourceId,
            UUID versionId,
            String contentDigest,
            String sourceRevision,
            Instant observedAt,
            UUID contentRefId,
            UUID incarnationId,
            String contentCacheReference) {
        this(tenantId, connectorId, sourceId, resourceId, versionId, contentDigest,
                sourceRevision, observedAt, contentRefId, incarnationId, contentCacheReference,
                0L, contentDigest, null);
    }
}

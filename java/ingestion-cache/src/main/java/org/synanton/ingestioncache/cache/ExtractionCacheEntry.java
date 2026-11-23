package org.synanton.ingestioncache.cache;

import java.time.Instant;
import java.util.UUID;

/** Cached extraction candidates keyed by contract identity, retaining their original generation. */
public record ExtractionCacheEntry(
        String tenantId,
        UUID sourceVersionId,
        String cacheKey,
        UUID generationId,
        String entitiesJson,
        String relationsJson,
        String promptVersion,
        String promptCompiledDigest,
        String promptRenderedDigest,
        String schemaVersion,
        String schemaDigest,
        Instant createdAt) {}

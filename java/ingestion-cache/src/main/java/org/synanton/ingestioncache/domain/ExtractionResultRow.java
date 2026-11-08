package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Pre-Relix extraction output for one chunk (PR #2 stub; not the graph commit path). */
public record ExtractionResultRow(
        String tenantId,
        UUID contentRefId,
        int chunkOrdinal,
        UUID sourceVersionId,
        String entitiesJson,
        String relationsJson,
        Instant extractedAt) {}

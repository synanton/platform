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
        Instant extractedAt,
        String promptVersion,
        String promptCompiledDigest,
        String promptRenderedDigest,
        String schemaVersion,
        String schemaDigest,
        UUID extractionGeneration,
        String finishReason,
        String gatewayRequestId,
        String gatewayExecutionId) {

    public ExtractionResultRow(String tenantId, UUID contentRefId, int chunkOrdinal,
                               UUID sourceVersionId, String entitiesJson,
                               String relationsJson, Instant extractedAt) {
        this(tenantId, contentRefId, chunkOrdinal, sourceVersionId, entitiesJson,
                relationsJson, extractedAt, null, null, null, null, null, null,
                null, null, null);
    }

    public ExtractionResultRow(String tenantId, UUID contentRefId, int chunkOrdinal,
                               UUID sourceVersionId, String entitiesJson, String relationsJson,
                               Instant extractedAt, String promptVersion,
                               String promptCompiledDigest, String promptRenderedDigest,
                               String schemaVersion, String schemaDigest) {
        this(tenantId, contentRefId, chunkOrdinal, sourceVersionId, entitiesJson,
                relationsJson, extractedAt, promptVersion, promptCompiledDigest,
                promptRenderedDigest, schemaVersion, schemaDigest, null, null, null, null);
    }

    public ExtractionResultRow(String tenantId, UUID contentRefId, int chunkOrdinal,
                               UUID sourceVersionId, String entitiesJson, String relationsJson,
                               Instant extractedAt, String promptVersion,
                               String promptCompiledDigest, String promptRenderedDigest,
                               String schemaVersion, String schemaDigest,
                               UUID extractionGeneration) {
        this(tenantId, contentRefId, chunkOrdinal, sourceVersionId, entitiesJson,
                relationsJson, extractedAt, promptVersion, promptCompiledDigest,
                promptRenderedDigest, schemaVersion, schemaDigest, extractionGeneration,
                null, null, null);
    }
}

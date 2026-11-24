package org.synanton.ingestioncache.quarantine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One versioned extraction generation for a source version. */
public record QuarantineGeneration(
        String tenantId,
        UUID sourceVersionId,
        UUID extractionGeneration,
        String sourceDocumentId,
        String promptVersion,
        String promptDigest,
        String schemaDigest,
        String modelId,
        String modelRevision,
        Instant extractionStartedAt,
        UUID extractionRunId,
        String generationStatus,
        UUID supersededBy,
        Instant promotedAt,
        String promotedBy,
        String promotionReason,
        UUID previousGeneration,
        String deletionMode,
        Instant deletedAt,
        String deletedReason,
        String ontologyReleaseId,
        String ontologyReleaseDigest) {

    public QuarantineGeneration(String tenantId, UUID sourceVersionId, UUID extractionGeneration,
                                String sourceDocumentId, String promptVersion, String promptDigest,
                                String schemaDigest, String modelId, String modelRevision,
                                Instant extractionStartedAt, UUID extractionRunId,
                                String generationStatus, UUID supersededBy, Instant promotedAt,
                                String promotedBy, String promotionReason, UUID previousGeneration,
                                String deletionMode, Instant deletedAt, String deletedReason) {
        this(tenantId, sourceVersionId, extractionGeneration, sourceDocumentId, promptVersion,
                promptDigest, schemaDigest, modelId, modelRevision, extractionStartedAt,
                extractionRunId, generationStatus, supersededBy, promotedAt, promotedBy,
                promotionReason, previousGeneration, deletionMode, deletedAt, deletedReason,
                null, null);
    }

    public static QuarantineGeneration staged(
            String tenantId, UUID sourceVersionId, UUID extractionGeneration,
            String sourceDocumentId, String promptVersion, String promptDigest,
            String schemaDigest, String modelId, String modelRevision,
            Instant extractionStartedAt, UUID extractionRunId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
        Objects.requireNonNull(extractionGeneration, "extractionGeneration");
        Objects.requireNonNull(extractionStartedAt, "extractionStartedAt");
        return staged(tenantId, sourceVersionId, extractionGeneration, sourceDocumentId,
                promptVersion, promptDigest, schemaDigest, modelId, modelRevision,
                extractionStartedAt, extractionRunId, null, null);
    }

    public static QuarantineGeneration staged(
            String tenantId, UUID sourceVersionId, UUID extractionGeneration,
            String sourceDocumentId, String promptVersion, String promptDigest,
            String schemaDigest, String modelId, String modelRevision,
            Instant extractionStartedAt, UUID extractionRunId,
            String ontologyReleaseId, String ontologyReleaseDigest) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
        Objects.requireNonNull(extractionGeneration, "extractionGeneration");
        Objects.requireNonNull(extractionStartedAt, "extractionStartedAt");
        return new QuarantineGeneration(tenantId, sourceVersionId, extractionGeneration,
                sourceDocumentId, promptVersion, promptDigest, schemaDigest, modelId,
                modelRevision, extractionStartedAt, extractionRunId, "STAGED",
                null, null, null, null, null, null, null, null,
                ontologyReleaseId, ontologyReleaseDigest);
    }
}

package org.synanton.ingestioncache.quarantine;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** One quarantined model-output line, scoped to an extraction generation. */
public record QuarantineRecord(
        String tenantId,
        UUID sourceVersionId,
        UUID extractionGeneration,
        int chunkOrdinal,
        int lineNumber,
        String reasonCode,
        String category,
        String detail,
        String rawLineSha256,
        String rawLine,
        Instant capturedAt,
        String disposition,
        Instant dispositionAt,
        String dispositionBy,
        String dispositionReason,
        Instant retentionExpiresAt) {

    public static QuarantineRecord open(
            String tenantId, UUID sourceVersionId, UUID extractionGeneration,
            int chunkOrdinal, int lineNumber, String reasonCode, String category,
            String detail, String rawLineSha256, String rawLine, Instant capturedAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
        Objects.requireNonNull(extractionGeneration, "extractionGeneration");
        Objects.requireNonNull(capturedAt, "capturedAt");
        return new QuarantineRecord(tenantId, sourceVersionId, extractionGeneration,
                chunkOrdinal, lineNumber, reasonCode, category, detail, rawLineSha256,
                rawLine, capturedAt, "OPEN", null, null, null,
                capturedAt.plus(30, ChronoUnit.DAYS));
    }
}

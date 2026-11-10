package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Durable mention-level resolution decision (PR #4a). */
public record ResolutionResultRow(
        String tenantId,
        UUID sourceVersionId,
        String mentionId,
        UUID contentRefId,
        int chunkOrdinal,
        int chunkOffsetStart,
        int chunkOffsetEnd,
        int documentOffsetStart,
        int documentOffsetEnd,
        String surfaceForm,
        String proposedType,
        String outcome,
        String reasonCode,
        String resolvedEntityId,
        String canonicalLabel,
        double bestScore,
        double runnerUpScore,
        String strategyVersion,
        String ontologyVersion,
        String diagnosticsJson,
        Instant decidedAt,
        Instant supersededAt,
        String searchGeneration) {

    /** Backward-compatible constructor: no search generation. */
    public ResolutionResultRow(String tenantId, UUID sourceVersionId, String mentionId,
                               UUID contentRefId, int chunkOrdinal, int chunkOffsetStart,
                               int chunkOffsetEnd, int documentOffsetStart, int documentOffsetEnd,
                               String surfaceForm, String proposedType, String outcome,
                               String reasonCode, String resolvedEntityId, String canonicalLabel,
                               double bestScore, double runnerUpScore, String strategyVersion,
                               String ontologyVersion, String diagnosticsJson, Instant decidedAt,
                               Instant supersededAt) {
        this(tenantId, sourceVersionId, mentionId, contentRefId, chunkOrdinal, chunkOffsetStart,
                chunkOffsetEnd, documentOffsetStart, documentOffsetEnd, surfaceForm, proposedType,
                outcome, reasonCode, resolvedEntityId, canonicalLabel, bestScore, runnerUpScore,
                strategyVersion, ontologyVersion, diagnosticsJson, decidedAt, supersededAt, null);
    }
}

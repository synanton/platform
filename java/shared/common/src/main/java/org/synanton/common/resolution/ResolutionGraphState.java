package org.synanton.common.resolution;

/**
 * Exact persisted state of one {@code :RESOLVES_TO} edge (PR #578). The
 * reconciliation path must compare resolution id, entity id, source version,
 * and fence; a mention id alone is not sufficient authorization.
 */
public record ResolutionGraphState(
        String tenantId,
        String mentionId,
        String sourceVersionId,
        String resolutionId,
        String entityId,
        long fencingToken,
        boolean active,
        String status,
        String policyVersion,
        String ontologyVersion,
        double bestScore,
        double runnerUpScore,
        String retrievalStrategy,
        String decidedAt) {}

package org.synanton.relix.reconciliation;

/** Minimal active-edge observation for one mention identity. No collections, no history traversal. */
public record GraphResolutionObservation(
        String sourceVersionId,
        String resolutionId,
        String entityId,
        long fencingToken,
        String policyVersion,
        String ontologyVersion,
        double bestScore,
        double runnerUpScore,
        String retrievalStrategy) {}

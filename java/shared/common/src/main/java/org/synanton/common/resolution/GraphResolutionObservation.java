package org.synanton.common.resolution;

/**
 * Minimal active-edge observation for one mention identity. No collections.
 * {@code multipleActive} surfaces a pre-existing invariant corruption instead
 * of silently selecting one edge.
 */
public record GraphResolutionObservation(
        String sourceVersionId,
        String resolutionId,
        String entityId,
        long fencingToken,
        String policyVersion,
        String ontologyVersion,
        double bestScore,
        double runnerUpScore,
        String retrievalStrategy,
        boolean multipleActive,
        String searchGeneration) {

    /** Backward-compatible constructor: no search generation. */
    public GraphResolutionObservation(String sourceVersionId, String resolutionId, String entityId,
                                      long fencingToken, String policyVersion, String ontologyVersion,
                                      double bestScore, double runnerUpScore, String retrievalStrategy,
                                      boolean multipleActive) {
        this(sourceVersionId, resolutionId, entityId, fencingToken, policyVersion, ontologyVersion,
                bestScore, runnerUpScore, retrievalStrategy, multipleActive, null);
    }
}

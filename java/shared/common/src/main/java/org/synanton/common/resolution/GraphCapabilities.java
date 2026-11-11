package org.synanton.common.resolution;

/** Explicit graph guarantees; a method existing is not a capability. */
public record GraphCapabilities(
        boolean supportsConditionalWrites,
        boolean supportsAtomicMultiNodeWrite,
        boolean supportsReadAfterWrite,
        boolean supportsIdempotentUpsert) {

    public static final GraphCapabilities NONE =
            new GraphCapabilities(false, false, false, false);

    public static GraphCapabilities neo4j() {
        return new GraphCapabilities(true, true, true, true);
    }

    /** Minimum guarantees required by the reconciliation workflow. */
    public boolean supportsResolutionReconciliation() {
        return supportsConditionalWrites && supportsReadAfterWrite && supportsIdempotentUpsert;
    }

    /** Minimum guarantees required by canonical relation promotion. */
    public boolean supportsRelationPromotion() {
        return supportsAtomicMultiNodeWrite && supportsReadAfterWrite && supportsIdempotentUpsert;
    }
}

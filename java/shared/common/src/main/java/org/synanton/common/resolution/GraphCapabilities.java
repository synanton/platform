package org.synanton.common.resolution;

/**
 * Explicit graph guarantees. A method existing on a port does not imply any of
 * these capabilities; consumers must gate on these values.
 */
public record GraphCapabilities(
        boolean supportsConditionalWrites,
        boolean supportsAtomicMultiNodeWrite,
        boolean supportsReadAfterWrite,
        boolean supportsIdempotentUpsert,
        boolean supportsMultiGraph) {

    public static final GraphCapabilities NONE =
            new GraphCapabilities(false, false, false, false, false);

    public GraphCapabilities(boolean supportsConditionalWrites,
                             boolean supportsAtomicMultiNodeWrite,
                             boolean supportsReadAfterWrite,
                             boolean supportsIdempotentUpsert) {
        this(supportsConditionalWrites, supportsAtomicMultiNodeWrite,
                supportsReadAfterWrite, supportsIdempotentUpsert, false);
    }

    public static GraphCapabilities neo4j() {
        return new GraphCapabilities(true, true, true, true, true);
    }

    /** Reconciliation requires fencing/conditional writes and idempotent upsert. */
    public boolean supportsResolutionReconciliation() {
        return supportsConditionalWrites && supportsIdempotentUpsert;
    }

    /** Promotion requires fencing, atomic multi-node writes, and idempotent upsert. */
    public boolean supportsRelationPromotion() {
        return supportsConditionalWrites && supportsAtomicMultiNodeWrite && supportsIdempotentUpsert;
    }

    /** Atomic multi-node writes must be paired with conditional-write fencing. */
    public boolean compositionIsConsistent() {
        return !supportsAtomicMultiNodeWrite || supportsConditionalWrites;
    }
}

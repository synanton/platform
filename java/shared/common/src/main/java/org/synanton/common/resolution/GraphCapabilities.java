package org.synanton.common.resolution;

/**
 * Explicit graph guarantees; a method existing is not a capability.
 *
 * <p>{@code supportsConditionalWrites}: the adapter rejects a write whose
 * fencing token is lower than the persisted token for the same identity; it
 * throws a stale-fencing error, never silently overwrites or no-ops.
 *
 * <p>{@code supportsAtomicMultiNodeWrite}: all nodes/relations/evidence in one
 * promotion call commit or roll back together; partial writes are not observable.
 *
 * <p>{@code supportsReadAfterWrite}: a write is visible to a subsequent read by
 * the same caller/transaction. This does not promise cross-replica visibility.
 *
 * <p>{@code supportsIdempotentUpsert}: deterministic operation identity means
 * the same upsert twice yields one persisted item; concurrent duplicates converge
 * to a deterministic final state.
 */
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

    /** Reconciliation requires fencing/conditional writes and idempotent upsert. */
    public boolean supportsResolutionReconciliation() {
        return supportsConditionalWrites && supportsIdempotentUpsert;
    }

    /** Promotion requires fencing, atomic multi-node writes, and idempotent upsert. */
    public boolean supportsRelationPromotion() {
        return supportsConditionalWrites && supportsAtomicMultiNodeWrite && supportsIdempotentUpsert;
    }
}

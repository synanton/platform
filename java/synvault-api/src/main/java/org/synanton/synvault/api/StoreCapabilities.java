package org.synanton.synvault.api;

import java.util.Objects;

/**
 * Capability claims for a {@link SynvaultStore} adapter (proposal §9.2–§9.3).
 *
 * <p>Every flag is a claim gated by the adapter's conformance/contract suite before
 * production enablement (YDB-POC-020). Until evidence exists the flag reports
 * {@code false}. Domain code must not branch on these flags outside
 * {@code *Configuration} / {@code *Provider} classes (YDB-POC-012).
 */
public record StoreCapabilities(
        boolean supportsTransactions,
        ConsistencyLevel consistency,
        boolean supportsJsonFilters,
        boolean supportsStorageRevisions,
        boolean supportsProvenance,
        boolean supportsCursorPagination) {
    public StoreCapabilities {
        Objects.requireNonNull(consistency, "consistency");
    }

    /** Minimum contract every adapter in this PoC must satisfy. */
    public static StoreCapabilities minimum() {
        return new StoreCapabilities(false, ConsistencyLevel.EVENTUAL, false, true, true, true);
    }
}

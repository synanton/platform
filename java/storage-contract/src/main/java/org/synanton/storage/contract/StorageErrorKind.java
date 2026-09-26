package org.synanton.storage.contract;

/**
 * Machine-readable failure kinds for storage-port operations. Adapters map
 * backend-specific errors onto these kinds; domain code branches only on kind.
 */
public enum StorageErrorKind {
    /** Entity not found in the caller's tenant scope (no existence leak across tenants). */
    NOT_FOUND,
    /** Optimistic-concurrency conflict on storage revision. */
    CONFLICT,
    /** Requested capability is not supported by this adapter. */
    UNSUPPORTED,
    /** Security or eligibility violation (also see {@link SecurityException}). */
    FORBIDDEN,
    /** Transient backend failure; the caller may retry. */
    TRANSIENT,
    /** Backend unavailable or misconfigured. */
    UNAVAILABLE
}

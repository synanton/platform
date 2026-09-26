package org.synanton.storage.contract;

/**
 * Conformance status of a single capability in a {@link ConformanceMatrix} (§9.3).
 */
public enum ConformanceStatus {
    /** Claimed and backed by a passing contract test (evidence names the test class). */
    SUPPORTED,
    /** Explicitly not provided; callers must not rely on it (e.g. Cassandra revision). */
    UNSUPPORTED,
    /** Claimed without passing-test evidence; must report {@code false} and cannot
     * be enabled in production. */
    UNVERIFIED
}

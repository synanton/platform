package org.synanton.relix.identity;

public enum SourceFenceStatus {
    CONVERGED,
    INITIALIZED,
    ADVANCED,
    STALE_SNAPSHOT,
    DIVERGENT,
    MISSING_AUTHORITY,
    UNAVAILABLE
}

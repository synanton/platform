package org.synanton.storage.contract;

/**
 * Freshness port: pairs Synvault commit timestamps with Synquest search-visible
 * timestamps per publication record. Relay wiring lands in Phase 3 (029), which
 * consumes this type — defined here so 038 and 029 cannot disagree on shape.
 */
public interface FreshnessTracker {

    void recordCommit(String revisionId, long epochMillis);

    void recordVisible(String revisionId, long epochMillis);

    FreshnessLag lag();
}

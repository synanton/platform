package org.synanton.storage.contract;

import java.util.Optional;

/**
 * Commit → search-visible freshness tracking (§8.3, YDB-POC-038). The relay records
 * both ends; Phase 3 (029) wires it to the publication consumer and gates on the lag.
 *
 * @param revisionId      publication-record identity (commit-sequence key)
 * @param lastLagMs       lag of the most recently completed pair; empty if none completed
 * @param maxLagMs        maximum observed lag; empty if none completed
 * @param pendingCommits  commits recorded without a matching visible event
 */
public record FreshnessLag(String revisionId, Optional<Long> lastLagMs, Optional<Long> maxLagMs, long pendingCommits) {
}

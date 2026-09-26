package org.synanton.storage.contract;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory freshness tracker (dev/test default; relay wiring in 029). */
public final class InMemoryFreshnessTracker implements FreshnessTracker {

    private final ConcurrentHashMap<String, Long> commits = new ConcurrentHashMap<>();
    private volatile long maxLagMs;
    private volatile boolean maxSet;
    private volatile String lastRevision;
    private volatile long lastLagMs;
    private volatile boolean lastSet;

    @Override
    public void recordCommit(String revisionId, long epochMillis) {
        commits.put(revisionId, epochMillis);
    }

    @Override
    public void recordVisible(String revisionId, long epochMillis) {
        Long committed = commits.remove(revisionId);
        if (committed == null) {
            return;
        }
        long lag = Math.max(0, epochMillis - committed);
        lastRevision = revisionId;
        lastLagMs = lag;
        lastSet = true;
        if (!maxSet || lag > maxLagMs) {
            maxLagMs = lag;
            maxSet = true;
        }
    }

    @Override
    public FreshnessLag lag() {
        return new FreshnessLag(
                lastRevision,
                lastSet ? Optional.of(lastLagMs) : Optional.empty(),
                maxSet ? Optional.of(maxLagMs) : Optional.empty(),
                commits.size());
    }

    public Map<String, Long> pendingForTesting() {
        return Map.copyOf(commits);
    }
}

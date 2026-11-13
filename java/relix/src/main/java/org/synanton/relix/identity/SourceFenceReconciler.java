package org.synanton.relix.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Reconciles Neo4j's persisted fence monotonically toward Cassandra's
 * authoritative accepted generation. This does not authorize promotion.
 */
public final class SourceFenceReconciler {

    private static final Logger log = LoggerFactory.getLogger(SourceFenceReconciler.class);

    private final SourceGenerationAuthority authority;
    private final SourceFenceStore store;
    private final Clock clock;

    public SourceFenceReconciler(SourceGenerationAuthority authority, SourceFenceStore store) {
        this(authority, store, Clock.systemUTC());
    }

    public SourceFenceReconciler(SourceGenerationAuthority authority, SourceFenceStore store,
                                 Clock clock) {
        this.authority = authority;
        this.store = store;
        this.clock = clock;
    }

    public SourceFenceReconciliationResult reconcile(SourceFenceKey key) {
        Instant now = clock.instant();
        Optional<Long> accepted;
        try {
            accepted = authority.acceptedGeneration(key);
        } catch (RuntimeException e) {
            log.warn("source.fence.reconcile.unavailable key={} reason={}", key, e.toString());
            return new SourceFenceReconciliationResult(key, SourceFenceStatus.UNAVAILABLE,
                    null, null, now, e.getMessage());
        }
        if (accepted.isEmpty()) {
            log.warn("source.fence.reconcile.missing_authority key={}", key);
            return new SourceFenceReconciliationResult(key, SourceFenceStatus.MISSING_AUTHORITY,
                    null, null, now, "no authoritative accepted generation");
        }
        long target = accepted.get();
        Optional<Long> existing;
        try {
            existing = store.read(key);
        } catch (RuntimeException e) {
            log.warn("source.fence.reconcile.unavailable key={} reason={}", key, e.toString());
            return new SourceFenceReconciliationResult(key, SourceFenceStatus.UNAVAILABLE,
                    target, null, now, e.getMessage());
        }
        if (existing.isEmpty()) {
            return write(key, target, target, SourceFenceStatus.INITIALIZED, now, "fence initialized from Cassandra");
        }
        long neo4j = existing.get();
        if (neo4j == target) {
            return new SourceFenceReconciliationResult(key, SourceFenceStatus.CONVERGED,
                    target, neo4j, now, null);
        }
        if (neo4j > target) {
            log.error("source.fence.reconcile.diverged key={} cassandra={} neo4j={}",
                    key, target, neo4j);
            return new SourceFenceReconciliationResult(key, SourceFenceStatus.DIVERGENT,
                    target, neo4j, now, "Neo4j fence is ahead; refusing to lower");
        }
        return write(key, target, target, SourceFenceStatus.ADVANCED, now, "fence advanced from Cassandra");
    }

    private SourceFenceReconciliationResult write(SourceFenceKey key, long target,
                                                  long reported, SourceFenceStatus status,
                                                  Instant now, String detail) {
        try {
            long actual = store.advance(key, target);
            if (actual != target) {
                log.error("source.fence.reconcile.diverged key={} target={} actual={}",
                        key, target, actual);
                return new SourceFenceReconciliationResult(key, SourceFenceStatus.DIVERGENT,
                        target, actual, now, "Neo4j did not advance to the requested generation");
            }
            log.info("source.fence.reconcile.{} key={} generation={}", status.name().toLowerCase(),
                    key, target);
            return new SourceFenceReconciliationResult(key, status, target, actual, now, detail);
        } catch (RuntimeException e) {
            log.warn("source.fence.reconcile.unavailable key={} reason={}", key, e.toString());
            return new SourceFenceReconciliationResult(key, SourceFenceStatus.UNAVAILABLE,
                    target, reported, now, e.getMessage());
        }
    }
}

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
            return result(key, SourceFenceStatus.UNAVAILABLE, null, null, null, now, e.getMessage());
        }
        if (accepted.isEmpty()) {
            log.warn("source.fence.reconcile.missing_authority key={}", key);
            return result(key, SourceFenceStatus.MISSING_AUTHORITY, null, null, null, now,
                    "no authoritative accepted generation");
        }
        long target = accepted.get();
        Optional<Long> existing;
        try {
            existing = store.read(key);
        } catch (RuntimeException e) {
            log.warn("source.fence.reconcile.unavailable key={} reason={}", key, e.toString());
            return result(key, SourceFenceStatus.UNAVAILABLE, target, target, null, now, e.getMessage());
        }

        if (existing.isPresent() && existing.get() > target) {
            log.error("source.fence.reconcile.diverged key={} cassandra={} neo4j={}",
                    key, target, existing.get());
            return result(key, SourceFenceStatus.DIVERGENT, target, target, existing.get(), now,
                    "Neo4j fence is ahead; refusing to lower");
        }
        if (existing.isPresent() && existing.get() == target) {
            return verifyAuthority(key, target, target, SourceFenceStatus.CONVERGED, now);
        }

        try {
            SourceFenceAdvance advance = store.advance(key, target);
            if (advance.generation() != target) {
                log.error("source.fence.reconcile.diverged key={} target={} actual={}",
                        key, target, advance.generation());
                return result(key, SourceFenceStatus.DIVERGENT, target, target,
                        advance.generation(), now, "Neo4j did not advance to the requested generation");
            }
            SourceFenceStatus status = existing.isEmpty() && advance.created()
                    ? SourceFenceStatus.INITIALIZED : SourceFenceStatus.ADVANCED;
            return verifyAuthority(key, target, advance.generation(), status, now);
        } catch (RuntimeException e) {
            log.warn("source.fence.reconcile.unavailable key={} reason={}", key, e.toString());
            return result(key, SourceFenceStatus.UNAVAILABLE, target, target, null, now, e.getMessage());
        }
    }

    private SourceFenceReconciliationResult verifyAuthority(SourceFenceKey key, long target,
                                                             long neo4j, SourceFenceStatus status,
                                                             Instant now) {
        Optional<Long> latest;
        try {
            latest = authority.acceptedGeneration(key);
        } catch (RuntimeException e) {
            return result(key, SourceFenceStatus.UNAVAILABLE, target, target, neo4j, now, e.getMessage());
        }
        if (latest.isEmpty()) {
            return result(key, SourceFenceStatus.MISSING_AUTHORITY, null, target, neo4j, now,
                    "authority disappeared after reconciliation");
        }
        if (latest.get() > target) {
            log.warn("source.fence.reconcile.stale_snapshot key={} evaluated={} latest={}",
                    key, target, latest.get());
            return result(key, SourceFenceStatus.STALE_SNAPSHOT, latest.get(), target, neo4j, now,
                    "reconciled to a snapshot that is no longer the latest authority");
        }
        if (latest.get() < target) {
            return result(key, SourceFenceStatus.DIVERGENT, latest.get(), target, neo4j, now,
                    "Neo4j fence is ahead of the latest authority");
        }
        log.info("source.fence.reconcile.{} key={} generation={}", status.name().toLowerCase(), key, target);
        return result(key, status, target, target, neo4j, now, null);
    }

    private static SourceFenceReconciliationResult result(SourceFenceKey key,
                                                          SourceFenceStatus status,
                                                          Long acceptedGeneration,
                                                          Long evaluatedGeneration,
                                                          Long neo4jGeneration,
                                                          Instant now, String detail) {
        return new SourceFenceReconciliationResult(key, status, acceptedGeneration,
                evaluatedGeneration, neo4jGeneration, now, detail);
    }
}

package org.synanton.relix.reconciliation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ResolutionResultRow;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.synanton.relix.config.ReconciliationProperties;

/**
 * PR #578: crash-window reconciliation only. It starts from Cassandra work rows,
 * compares exact graph identity/fence, repairs interrupted operations, and
 * records decision inconsistencies without mutating them.
 *
 * <p>Transitional #578/#587 seam. Capability assumptions (to be formalized and
 * startup-validated by #587): exact-identity reads, conditional graph writes with
 * monotonic fencing, read-after-write consistency for the active resolution edge,
 * deterministic idempotent graph identities, and Cassandra LWT owner/lease
 * ownership. The reconciler uses only single-identity graph observations and
 * intent verbs; all Cypher remains in the Neo4j adapter. Delete this seam when
 * #590 migrates #585 onto #587 contracts; deviation #591.
 */
public final class ResolutionReconciler {

    private static final Logger log = LoggerFactory.getLogger(ResolutionReconciler.class);

    private final IngestionCacheClient cache;
    private final ClaimService claims;
    private final ResolutionReconciliationGraph graph;
    private final ReconciliationProperties properties;
    private final ResolutionReconciliationPolicy policy = new ResolutionReconciliationPolicy();
    private final Clock clock = Clock.systemUTC();
    private final String workerId = "reconciler-" + UUID.randomUUID();
    private final Counter graphRepaired;
    private final Counter cassandraRepaired;
    private final Counter staleEdge;
    private final Counter contradiction;
    private final Counter errors;
    private final Counter skipped;
    private final Counter leaseHeld;

    public ResolutionReconciler(IngestionCacheClient cache, ClaimService claims,
                                ResolutionReconciliationGraph graph,
                                ReconciliationProperties properties, MeterRegistry registry) {
        this.cache = cache;
        this.claims = claims;
        this.graph = graph;
        this.properties = properties;
        this.graphRepaired = registry.counter("reconciliation.graph_repaired");
        this.cassandraRepaired = registry.counter("reconciliation.cassandra_repaired");
        this.staleEdge = registry.counter("reconciliation.stale_edge");
        this.contradiction = registry.counter("reconciliation.contradiction");
        this.errors = registry.counter("reconciliation.errors");
        this.skipped = registry.counter("reconciliation.skipped");
        this.leaseHeld = registry.counter("reconciliation.lease_held");
    }

    @Scheduled(fixedDelayString = "${relix.reconciliation.poll-delay-ms:60000}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        String tenant = properties.tenant();
        if (tenant == null || tenant.isBlank()) {
            log.warn("Reconciliation is enabled without a tenant; skipping poll");
            return;
        }
        // Transitional: assumes exact-identity read + fencing-conditional write.
        // Enforced at startup validation in #587. See deviation #591.
        List<ResolutionWorkRow> work;
        try {
            work = claims.resolutionWorkForTenant(tenant);
        } catch (Exception e) {
            errors.increment();
            log.warn("Reconciliation scan failed tenant={}: {}", tenant, e.toString());
            return;
        }
        Map<UUID, Map<String, ResolutionResultRow>> decisions = new HashMap<>();
        Instant now = clock.instant();
        int processed = 0;
        for (ResolutionWorkRow row : work) {
            if (processed++ >= properties.batchOrDefault()) {
                break;
            }
            ResolutionResultRow result;
            try {
                result = decisions.computeIfAbsent(row.sourceVersionId(), version ->
                                decisionMap(tenant, version))
                        .get(row.mentionId());
            } catch (Exception e) {
                errors.increment();
                log.warn("Reconciliation decision load failed tenant={} version={}: {}",
                        tenant, row.sourceVersionId(), e.toString());
                continue;
            }
            if (!"COMPLETED".equals(row.claimStatus()) && !"PROCESSING".equals(row.claimStatus())) {
                continue;
            }
            reconcile(row, result, now);
        }
    }

    private Map<String, ResolutionResultRow> decisionMap(String tenant, UUID version) {
        Map<String, ResolutionResultRow> byMention = new HashMap<>();
        for (ResolutionResultRow row : cache.listResolutionResults(tenant, version)) {
            byMention.put(row.mentionId(), row);
        }
        return byMention;
    }

    private void reconcile(ResolutionWorkRow row, ResolutionResultRow result, Instant now) {
        ResolutionKey key = new ResolutionKey(row.tenantId(), row.mentionId());
        Optional<GraphResolutionObservation> active;
        boolean history;
        try {
            active = graph.activeResolution(key);
            history = active.isEmpty() && graph.hasResolutionHistory(key);
        } catch (Exception e) {
            errors.increment();
            log.warn("Reconciliation graph observation failed tenant={} mention={}: {}",
                    row.tenantId(), row.mentionId(), e.toString());
            return;
        }
        ReconciliationAction action = policy.decide(row, result, active, history, now);
        if (action.kind() == ReconciliationKind.NOOP
                && "PROCESSING".equals(row.claimStatus())
                && row.leaseExpiresAt() != null && row.leaseExpiresAt().isAfter(now)) {
            leaseHeld.increment();
        }
        switch (action.kind()) {
            case NOOP -> { }
            case REPAIR_GRAPH -> repairGraph(row, action);
            case REPAIR_CASSANDRA -> repairCassandra(row, action, now);
            case STALE_EDGE -> detectStaleEdge(row, result, action);
            case CONTRADICTION -> detectContradiction(row, result, action);
        }
    }

    private void repairGraph(ResolutionWorkRow row, ReconciliationAction action) {
        try {
            ResolutionWriteOutcome outcome = graph.ensureResolution(action.resolutionDecision());
            switch (outcome) {
                case APPLIED -> {
                    graphRepaired.increment();
                    log.info("Reconciled missing graph resolution tenant={} mention={} version={} "
                                    + "resolutionId={} fence={}", row.tenantId(), row.mentionId(),
                            row.sourceVersionId(), action.resolutionId(),
                            action.resolutionDecision().fencingToken());
                }
                case REJECTED -> {
                    contradiction.increment();
                    log.warn("Reconciliation graph intent rejected tenant={} mention={} version={} "
                                    + "resolutionId={}", row.tenantId(), row.mentionId(),
                            row.sourceVersionId(), action.resolutionId());
                }
                case RETRYABLE -> {
                    errors.increment();
                    log.warn("Reconciliation graph intent retryable tenant={} mention={}",
                            row.tenantId(), row.mentionId());
                }
            }
        } catch (Exception e) {
            errors.increment();
            log.warn("Reconciliation graph intent failed tenant={} mention={}: {}",
                    row.tenantId(), row.mentionId(), e.toString());
        }
    }

    private void repairCassandra(ResolutionWorkRow row, ReconciliationAction action, Instant now) {
        GraphResolutionObservation state = action.activeState();
        if (state == null) {
            contradiction.increment();
            return;
        }
        try {
            if (!claims.reclaimResolution(row, now)) {
                skipped.increment();
                return; // another worker reclaimed or the lease changed
            }
            if (!claims.claimResolution(row, workerId, now,
                    Duration.ofSeconds(properties.leaseSecondsOrDefault()))) {
                skipped.increment();
                return; // normal consumer won the claim
            }
            ResolutionDecision decision = action.resolutionDecision();
            if (!claims.completeResolution(row, workerId, decision, clock.instant())) {
                skipped.increment();
                return; // ownership lost; do not repair blindly
            }
            cassandraRepaired.increment();
            claims.refreshVersionStatus(row, clock.instant());
            log.info("Reconciled Cassandra completion from graph tenant={} mention={} version={} "
                            + "resolutionId={} entityId={} fence={}", row.tenantId(), row.mentionId(),
                    row.sourceVersionId(), state.resolutionId(), state.entityId(),
                    state.fencingToken());
        } catch (Exception e) {
            errors.increment();
            log.warn("Reconciliation Cassandra repair failed tenant={} mention={}: {}",
                    row.tenantId(), row.mentionId(), e.toString());
        }
    }

    private void detectStaleEdge(ResolutionWorkRow row, ResolutionResultRow result,
                                 ReconciliationAction action) {
        staleEdge.increment();
        GraphResolutionObservation state = action.activeState();
        log.warn("Reconciliation detected stale active edge tenant={} mention={} sourceVersion={} "
                        + "resolutionId={} entityId={} graphFence={} cassandraOutcome={} "
                        + "policyVersion={} ontologyVersion={} detail={}",
                row.tenantId(), row.mentionId(), row.sourceVersionId(),
                state == null ? null : state.resolutionId(),
                state == null ? null : state.entityId(),
                state == null ? null : state.fencingToken(),
                result == null ? null : result.outcome(),
                result == null ? null : result.strategyVersion(),
                result == null ? null : result.ontologyVersion(),
                action.detail());
    }

    private void detectContradiction(ResolutionWorkRow row, ResolutionResultRow result,
                                     ReconciliationAction action) {
        contradiction.increment();
        GraphResolutionObservation state = action.activeState();
        log.warn("Reconciliation detected contradictory state tenant={} mention={} sourceVersion={} "
                        + "resolutionId={} entityId={} graphFence={} cassandraOutcome={} "
                        + "policyVersion={} ontologyVersion={} detail={}",
                row.tenantId(), row.mentionId(), row.sourceVersionId(),
                action.resolutionId() != null ? action.resolutionId()
                        : state == null ? null : state.resolutionId(),
                state == null ? null : state.entityId(),
                state == null ? null : state.fencingToken(),
                result == null ? null : result.outcome(),
                result == null ? null : result.strategyVersion(),
                result == null ? null : result.ontologyVersion(),
                action.detail());
    }
}

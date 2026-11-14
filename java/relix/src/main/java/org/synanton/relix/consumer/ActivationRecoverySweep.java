package org.synanton.relix.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.activation.ActivationRecord;
import org.synanton.ingestioncache.activation.ActivationState;
import org.synanton.ingestioncache.activation.SourceActivationStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ActivationRequestRow;
import org.synanton.relix.config.ActivationRecoveryProperties;
import org.synanton.relix.identity.ActivateRevision;

/**
 * Named recovery mechanism from ADR-029 §12.2: bounded scan of durable
 * GRAPH_COMMITTED/ActivationRequested rows, replay of missing projection state,
 * then idempotent {@link ActivateRevision}. Cassandra owns currentness; the
 * Neo4j fence is read as a precondition only and is never locked here.
 */
@Component
@ConditionalOnProperty(name = "relix.graph.connector", havingValue = "neo4j")
public final class ActivationRecoverySweep {

    private static final Logger log = LoggerFactory.getLogger(ActivationRecoverySweep.class);

    private final IngestionCacheClient cache;
    private final SourceActivationStore activations;
    private final ActivateRevision activateRevision;
    private final ActivationRecoveryProperties properties;
    private final MeterRegistry registry;
    private final Clock clock;

    public ActivationRecoverySweep(IngestionCacheClient cache, SourceActivationStore activations,
                                   ActivateRevision activateRevision,
                                   ActivationRecoveryProperties properties,
                                   MeterRegistry registry, Clock clock) {
        this.cache = cache;
        this.activations = activations;
        this.activateRevision = activateRevision;
        this.properties = properties;
        this.registry = registry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${relix.activation-recovery.poll-delay-ms:30000}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        String tenant = properties.tenant();
        if (tenant == null || tenant.isBlank()) {
            return;
        }
        for (ActivationRequestRow request : cache.listPendingActivationRequests(
                tenant, properties.batch(), clock.instant())) {
            sweep(request);
        }
    }

    /** Public for the ingestion-originated integration path and tests. */
    public void sweep(ActivationRequestRow request) {
        if (!"GRAPH_COMMITTED".equals(request.graphStatus())) {
            // MENTIONS_COMMITTED is not an activation boundary. Canonical completion
            // owns the promotion -> GRAPH_COMMITTED transition.
            return;
        }
        ActivationRecord record = activations.read(
                request.tenantId(), request.sourceId(), request.generation()).orElse(null);
        if (record == null) {
            fail(request, "activation record is not staged");
            return;
        }
        if (record.state() == ActivationState.CURRENT) {
            cache.markActivationEventPublished(request, clock.instant());
            counter("activation.recovered").increment();
            return;
        }
        if (record.state() == ActivationState.MENTIONS_COMMITTED) {
            // The durable event proves canonical completion; repair only the lost
            // activation transition. The sweep never synthesizes GRAPH_COMMITTED
            // from a MENTIONS_COMMITTED source row.
            record = transitionToGraphCommitted(record);
        }
        if (record == null || (record.state() != ActivationState.GRAPH_COMMITTED
                && record.state() != ActivationState.SEARCH_COMMITTING
                && record.state() != ActivationState.SEARCH_COMMITTED
                && record.state() != ActivationState.ACTIVATION_PARTIAL)) {
            // Extraction, mention commit, and canonical promotion own earlier states.
            return;
        }
        if (request.searchProjectedAt() == null) {
            // Search projection is not durable yet: leave the event pending without
            // consuming an attempt. The next bounded sweep re-checks readiness.
            return;
        }
        record = advanceToSearchCommitted(record);
        if (record == null) {
            fail(request, "activation state could not reach SEARCH_COMMITTED");
            return;
        }
        ActivateRevision.Result result = activateRevision.activate(new ActivateRevision.Request(
                request.tenantId(), request.sourceId(), request.generation(), request.versionId()));
        switch (result.status()) {
            case ACTIVATED, ALREADY_CURRENT -> {
                cache.markActivationEventPublished(request, clock.instant());
                counter("activation.recovered").increment();
            }
            case STALE_GENERATION -> {
                counter("activation.stale").increment();
                cache.markActivationEventFailed(request, Math.max(1, request.attemptCount() + 1),
                        result.message(), null, true);
            }
            default -> fail(request, result.message());
        }
    }

    private ActivationRecord transitionToGraphCommitted(ActivationRecord record) {
        activations.transition(record.tenantId(), record.sourceId(), record.generation(),
                ActivationState.MENTIONS_COMMITTED, ActivationState.GRAPH_COMMITTED,
                new SourceActivationStore.ProjectionMarkers(record.generation(), "COMMITTED",
                        record.searchAppliedGeneration(), record.searchAppliedState()),
                null, true, clock.instant());
        return activations.read(record.tenantId(), record.sourceId(), record.generation())
                .orElse(null);
    }

    private ActivationRecord advanceToSearchCommitted(ActivationRecord record) {
        if (record.state() == ActivationState.GRAPH_COMMITTED) {
            record = transition(record, ActivationState.SEARCH_COMMITTING,
                    record.graphAppliedGeneration(), false);
        }
        if (record != null && record.state() == ActivationState.SEARCH_COMMITTING) {
            record = transition(record, ActivationState.SEARCH_COMMITTED,
                    record.graphAppliedGeneration(), true);
        }
        return record;
    }

    private ActivationRecord transition(ActivationRecord record, ActivationState next,
                                        long graphGeneration, boolean searchCommitted) {
        long searchGeneration = searchCommitted ? record.generation() : record.searchAppliedGeneration();
        SourceActivationStore.ProjectionMarkers markers = new SourceActivationStore.ProjectionMarkers(
                graphGeneration, graphGeneration > 0 ? "COMMITTED" : record.graphAppliedState(),
                searchGeneration, searchGeneration > 0 ? "COMMITTED" : record.searchAppliedState());
        boolean moved = activations.transition(record.tenantId(), record.sourceId(),
                record.generation(), record.state(), next, markers, null, true, clock.instant());
        if (!moved) {
            return null;
        }
        return activations.read(record.tenantId(), record.sourceId(), record.generation())
                .orElse(null);
    }

    private void fail(ActivationRequestRow request, String error) {
        int attempt = request.attemptCount() + 1;
        boolean deadLettered = attempt >= properties.maxAttempts();
        Instant next = deadLettered ? null
                : clock.instant().plusSeconds(properties.retryBackoffSeconds() * (1L << Math.min(attempt - 1, 5)));
        cache.markActivationEventFailed(request, attempt, error, next, deadLettered);
        counter("activation.failed").increment();
        log.warn("Activation sweep deferred tenant={} source={} generation={} attempt={} error={}",
                request.tenantId(), request.sourceId(), request.generation(), attempt, error);
    }

    private io.micrometer.core.instrument.Counter counter(String name) {
        return io.micrometer.core.instrument.Counter.builder(name).register(registry);
    }
}

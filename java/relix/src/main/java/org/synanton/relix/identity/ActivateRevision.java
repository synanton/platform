package org.synanton.relix.identity;

import org.synanton.ingestioncache.activation.ActivationRecord;
import org.synanton.ingestioncache.activation.ActivationState;
import org.synanton.ingestioncache.activation.SourceActivationStore;
import org.synanton.ingestioncache.activation.SourceLifecycleRecord;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent activation of a staged source revision. Cassandra owns the activation
 * record and current pointer; Neo4j owns the source fence. The fence check is a
 * precondition, not a replacement for the record state.
 */
public final class ActivateRevision {

    private final SourceActivationStore activations;
    private final SourceFenceStore fences;
    private final Clock clock;

    public ActivateRevision(SourceActivationStore activations, SourceFenceStore fences) {
        this(activations, fences, Clock.systemUTC());
    }

    ActivateRevision(SourceActivationStore activations, SourceFenceStore fences, Clock clock) {
        this.activations = activations;
        this.fences = fences;
        this.clock = clock;
    }

    public Result activate(Request request) {
        Optional<ActivationRecord> maybeRecord = activations.read(
                request.tenantId(), request.sourceId(), request.generation());
        if (maybeRecord.isEmpty()) {
            return new Result(Status.NOT_STAGED, null, 0, false, "activation record is not staged");
        }
        ActivationRecord record = maybeRecord.get();
        Instant now = clock.instant();
        Optional<SourceLifecycleRecord> maybeLifecycle =
                activations.readLifecycle(request.tenantId(), request.sourceId());
        long current = maybeLifecycle.map(SourceLifecycleRecord::currentGeneration).orElse(0L);

        if (record.state() == ActivationState.CURRENT) {
            return new Result(Status.ALREADY_CURRENT, record.state(), current, false,
                    "generation is already current");
        }
        if (current > request.generation()) {
            return new Result(Status.STALE_GENERATION, record.state(), current, false,
                    "a newer generation is already current");
        }

        Optional<Long> fence = fences.read(new SourceFenceKey(request.tenantId(), request.sourceId()));
        if (fence.isEmpty() || fence.get() < request.generation()) {
            return new Result(Status.NOT_READY, record.state(), current, true,
                    "source fence has not reached generation " + request.generation());
        }
        if (fence.get() > request.generation()) {
            if (record.state().canTransitionTo(ActivationState.STALE_REJECTED)) {
                activations.transition(request.tenantId(), request.sourceId(), request.generation(),
                        record.state(), ActivationState.STALE_REJECTED,
                        SourceActivationStore.ProjectionMarkers.none(record),
                        "source fence advanced to generation " + fence.get(), false, now);
            }
            return new Result(Status.STALE_GENERATION, ActivationState.STALE_REJECTED, current, false,
                    "source fence is newer than activation generation");
        }

        if (record.state() == ActivationState.ACTIVATION_PARTIAL) {
            boolean moved = activations.transition(request.tenantId(), request.sourceId(),
                    request.generation(), ActivationState.ACTIVATION_PARTIAL,
                    ActivationState.SEARCH_COMMITTED, SourceActivationStore.ProjectionMarkers.none(record),
                    null, true, now);
            if (!moved) {
                return new Result(Status.ACTIVATION_PARTIAL, record.state(), current, true,
                        "activation partial state could not be retried");
            }
            record = activations.read(request.tenantId(), request.sourceId(), request.generation())
                    .orElse(record);
        }
        if (record.state() != ActivationState.SEARCH_COMMITTED) {
            return new Result(Status.NOT_READY, record.state(), current, record.retryEligible(),
                    "graph and search must be committed before activation");
        }

        boolean activated = activations.activate(request.tenantId(), request.sourceId(),
                request.generation(), request.revisionId(), now);
        if (activated) {
            ActivationRecord after = activations.read(request.tenantId(), request.sourceId(),
                    request.generation()).orElse(record);
            long afterCurrent = activations.readLifecycle(request.tenantId(), request.sourceId())
                    .map(SourceLifecycleRecord::currentGeneration).orElse(current);
            return new Result(Status.ACTIVATED, after.state(), afterCurrent, false,
                    "generation activated");
        }

        activations.transition(request.tenantId(), request.sourceId(), request.generation(),
                ActivationState.SEARCH_COMMITTED, ActivationState.ACTIVATION_PARTIAL,
                SourceActivationStore.ProjectionMarkers.none(record),
                "activation current-pointer flip failed", true, now);
        return new Result(Status.ACTIVATION_PARTIAL, ActivationState.ACTIVATION_PARTIAL, current, true,
                "activation is partial; retry after repairing the current pointer");
    }

    public enum Status {
        ACTIVATED,
        ALREADY_CURRENT,
        NOT_STAGED,
        NOT_READY,
        STALE_GENERATION,
        ACTIVATION_PARTIAL
    }

    public record Request(String tenantId, String sourceId, long generation, UUID revisionId) {
        public Request {
            if (tenantId == null || tenantId.isBlank() || sourceId == null || sourceId.isBlank()) {
                throw new IllegalArgumentException("tenant and source are required");
            }
            if (generation < 1 || revisionId == null) {
                throw new IllegalArgumentException("generation and revision are required");
            }
        }
    }

    public record Result(Status status, ActivationState state, long currentGeneration,
                         boolean retryEligible, String message) {}
}

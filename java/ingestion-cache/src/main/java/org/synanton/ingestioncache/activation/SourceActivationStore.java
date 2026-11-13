package org.synanton.ingestioncache.activation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Cassandra-authoritative activation record. Projection stores are not authority;
 * they carry applied markers that recovery replays toward this record.
 */
public interface SourceActivationStore {

    boolean stage(ActivationRecord record);

    Optional<ActivationRecord> read(String tenantId, String sourceId, long generation);

    Optional<ActivationRecord> readLatest(String tenantId, String sourceId);

    Optional<SourceLifecycleRecord> readLifecycle(String tenantId, String sourceId);

    /** CAS transition; returns false when the expected state no longer holds. */
    boolean transition(String tenantId, String sourceId, long generation,
                       ActivationState expected, ActivationState next,
                       ProjectionMarkers markers, String error, boolean retryEligible, Instant now);

    /** Idempotently flips the source current pointer to an already-committed generation. */
    boolean activate(String tenantId, String sourceId, long generation, UUID revisionId, Instant now);

    record ProjectionMarkers(long graphGeneration, String graphState,
                             long searchGeneration, String searchState) {
        public static ProjectionMarkers none(ActivationRecord record) {
            return new ProjectionMarkers(record.graphAppliedGeneration(), record.graphAppliedState(),
                    record.searchAppliedGeneration(), record.searchAppliedState());
        }
    }
}

package org.synanton.synflux.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * EWP-2 typed payload for the {@code SourceDeleted} event (schema v1). Field names
 * match the previous ad-hoc map so existing outbox rows remain readable.
 *
 * <p>Hazard H-1: out-of-order delivery can resurrect a deleted source (a stale
 * {@code SourcePublished} arriving after the {@code SourceDeleted}). The event is
 * still {@code REPLAY_SAFE} at the event level, but consumers must apply per-source
 * ordering (or compare versions) before re-materialising a source.
 */
public record SourceDeleted(
        @JsonProperty("source_identity") SourceIdentity sourceIdentity,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonProperty("deleted_at") Instant deletedAt,
        @JsonProperty("tombstone_id") UUID tombstoneId,
        @JsonProperty("versions_manifest_ref") UUID versionsManifestRef,
        @JsonProperty("run_id") UUID runId,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("workflow_id") String workflowId) {}

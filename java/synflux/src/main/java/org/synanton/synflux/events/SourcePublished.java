package org.synanton.synflux.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * EWP-2 typed payload for the {@code SourcePublished} event (schema v1). Replaces
 * the ad-hoc {@code Map} previously emitted by the ingestion pipeline; the wire
 * field names are unchanged so existing outbox rows remain readable.
 */
public record SourcePublished(
        @JsonProperty("source_identity") SourceIdentity sourceIdentity,
        @JsonProperty("version_id") UUID versionId,
        @JsonProperty("content_digest") String contentDigest,
        @JsonProperty("content_ref_id") UUID contentRefId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonProperty("observed_at") Instant observedAt,
        @JsonProperty("run_id") UUID runId,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("workflow_id") String workflowId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("content_cache_reference") String contentCacheReference) {}

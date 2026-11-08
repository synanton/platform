package org.synanton.synflux.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * EWP-2 typed payload for {@code ExtractionRequested@1} (wire label
 * {@code extraction.requested.v1}). Emitted with {@code SourcePublished} in the
 * same source-version LWT; the extraction consumer gates on
 * {@code source_versions.search_projected_at} before reading chunks.
 *
 * <p>Hazard H-2: at-least-once delivery / duplicate LWT retries can re-emit the
 * event. It is replay-safe and idempotent by
 * {@code (tenant, connector, source, resource, version_id)}.
 */
public record ExtractionRequested(
        @JsonProperty("source_identity") SourceIdentity sourceIdentity,
        @JsonProperty("version_id") UUID versionId,
        @JsonProperty("content_ref_id") UUID contentRefId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonProperty("observed_at") Instant observedAt) {}

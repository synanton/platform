package org.synanton.synflux.domain;

import org.synanton.extraction.v1.DocumentPayload;

import java.util.Map;

/**
 * @param featureStates what the extraction plane did with each requested
 *                      capability ({@code name -> FeatureState name}). The
 *                      Operation's anti-omission rule reads this: a requested
 *                      feature reported FEATURE_UNSUPPORTED must not surface
 *                      as {@code outcome: FULL} (#294).
 * @param payloadDescriptor what produced the payload (§12, §67.2):
 *                          schema_id/schema_version/processor_id/processor_version.
 *                          Persisted on the run for provenance.
 */
public record ParsedDocument(
    AcquiredDocument acquired,
    String text,
    Map<String, String> metadata,
    DocumentPayload documentPayload,
    Map<String, String> featureStates,
    Map<String, String> payloadDescriptor
) {
    /** Compatibility for producers that carry no extraction metadata. */
    public ParsedDocument(AcquiredDocument acquired, String text, Map<String, String> metadata,
                          DocumentPayload documentPayload) {
        this(acquired, text, metadata, documentPayload, Map.of(), Map.of());
    }
}

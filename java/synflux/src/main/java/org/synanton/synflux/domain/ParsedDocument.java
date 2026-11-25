package org.synanton.synflux.domain;

import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.synflux.document.DocumentStructure;

import java.util.LinkedHashMap;
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
 * @param structure parser-neutral source coordinates (R0.2/P1). Null when the
 *                  media type has no structural adapter.
 */
public record ParsedDocument(
    AcquiredDocument acquired,
    String text,
    Map<String, String> metadata,
    DocumentPayload documentPayload,
    Map<String, String> featureStates,
    Map<String, String> payloadDescriptor,
    DocumentStructure structure
) {
    public ParsedDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        featureStates = featureStates == null ? Map.of() : Map.copyOf(featureStates);
        payloadDescriptor = payloadDescriptor == null ? Map.of() : Map.copyOf(payloadDescriptor);
    }

    /** Compatibility for producers that carry no structural document. */
    public ParsedDocument(AcquiredDocument acquired, String text, Map<String, String> metadata,
                          DocumentPayload documentPayload, Map<String, String> featureStates,
                          Map<String, String> payloadDescriptor) {
        this(acquired, text, metadata, documentPayload, featureStates, payloadDescriptor, null);
    }

    /** Compatibility for producers that carry no extraction metadata. */
    public ParsedDocument(AcquiredDocument acquired, String text, Map<String, String> metadata,
                          DocumentPayload documentPayload) {
        this(acquired, text, metadata, documentPayload, Map.of(), Map.of(), null);
    }

    public ParsedDocument withStructure(DocumentStructure structure,
                                        Map<String, String> additionalMetadata) {
        Map<String, String> merged = new LinkedHashMap<>(metadata);
        if (additionalMetadata != null) {
            merged.putAll(additionalMetadata);
        }
        return new ParsedDocument(acquired, text, merged, documentPayload, featureStates,
            payloadDescriptor, structure);
    }
}

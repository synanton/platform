package org.synanton.extraction.engine.provenance;

import java.util.Objects;
import java.util.UUID;

/**
 * Contract-defined extraction cache identity (#831). It deliberately excludes
 * {@code extraction_generation}; generation is a record identity, not a cache
 * input.
 */
public record ExtractionCacheKey(
        String tenantId,
        UUID sourceVersionId,
        String segmentHash,
        String promptDigest,
        String schemaDigest,
        String modelId,
        String modelRevision,
        double temperature,
        double topP,
        String tokenizerVersion) {

    public ExtractionCacheKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
        Objects.requireNonNull(segmentHash, "segmentHash");
        Objects.requireNonNull(promptDigest, "promptDigest");
    }

    public static ExtractionCacheKey forRequest(
            String tenantId, UUID sourceVersionId, String segmentText,
            String promptDigest, String schemaDigest, String modelId,
            String modelRevision, double temperature, double topP,
            String tokenizerVersion) {
        return new ExtractionCacheKey(tenantId, sourceVersionId,
                PromptDigest.digest(segmentText), promptDigest, schemaDigest,
                modelId, modelRevision, temperature, topP, tokenizerVersion);
    }

    public String cacheKey() {
        return String.join("|",
                tenantId, sourceVersionId.toString(), segmentHash, promptDigest,
                String.valueOf(schemaDigest), String.valueOf(modelId),
                String.valueOf(modelRevision), Double.toString(temperature),
                Double.toString(topP), String.valueOf(tokenizerVersion));
    }
}

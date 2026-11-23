package org.synanton.extraction.engine;

import java.util.Optional;
import org.synanton.extraction.engine.provenance.ExtractionCacheKey;

/**
 * Engine-neutral extraction port (ports & adapters). Implementations may use
 * regex, an LLM, or a remote service; none of those types cross this boundary.
 * The result is candidate knowledge only — resolution/validation/commit happen
 * downstream.
 */
public interface ExtractionEngineAdapter {
    ExtractionResult extract(TextSegment segment, ExtractionContext context);

    /**
     * Cache identity for this segment, or empty when the adapter has no stable
     * cache contract. The consumer only uses a complete same-generation hit;
     * any miss or failure follows the normal extraction lifecycle.
     */
    default Optional<ExtractionCacheKey> cacheKey(TextSegment segment,
                                                  ExtractionContext context) {
        return Optional.empty();
    }
}

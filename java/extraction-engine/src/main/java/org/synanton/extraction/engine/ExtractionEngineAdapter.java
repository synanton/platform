package org.synanton.extraction.engine;

/**
 * Engine-neutral extraction port (ports & adapters). Implementations may use
 * regex, an LLM, or a remote service; none of those types cross this boundary.
 * The result is candidate knowledge only — resolution/validation/commit happen
 * downstream.
 */
public interface ExtractionEngineAdapter {
    ExtractionResult extract(TextSegment segment, ExtractionContext context);
}

package org.synanton.extraction.engine.llm;

import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.TextSegment;

/**
 * RAKG/LLM adapter placeholder (Phase B). It will send the segment to the GPU
 * plane and parse the JSON response into pre-entities/relations. Until the
 * model integration lands it returns an empty result, which keeps the wiring
 * and evaluation contract in place.
 *
 * TODO(RAKG): call the GPU plane, validate the JSON, populate preEntities/relations.
 */
public final class LlmExtractionAdapter implements ExtractionEngineAdapter {

    @Override
    public ExtractionResult extract(TextSegment segment, ExtractionContext context) {
        return ExtractionResult.empty();
    }
}

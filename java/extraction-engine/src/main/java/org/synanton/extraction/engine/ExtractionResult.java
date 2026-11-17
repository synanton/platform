package org.synanton.extraction.engine;

import java.util.List;

/** Raw extraction output; never committed to the graph directly. */
public record ExtractionResult(
        List<PreEntity> preEntities,
        List<RelationCandidate> relations,
        LlmUsage llmUsage) {

    public ExtractionResult(List<PreEntity> preEntities, List<RelationCandidate> relations) {
        this(preEntities, relations, LlmUsage.NONE);
    }

    public static ExtractionResult empty() {
        return new ExtractionResult(List.of(), List.of(), LlmUsage.NONE);
    }
}

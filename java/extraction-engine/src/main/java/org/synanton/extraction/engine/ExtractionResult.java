package org.synanton.extraction.engine;

import java.util.List;

/** Raw extraction output; never committed to the graph directly. */
public record ExtractionResult(List<PreEntity> preEntities, List<RelationCandidate> relations) {
    public static ExtractionResult empty() {
        return new ExtractionResult(List.of(), List.of());
    }
}

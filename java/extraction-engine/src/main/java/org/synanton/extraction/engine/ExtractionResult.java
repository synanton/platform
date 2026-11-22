package org.synanton.extraction.engine;

import java.util.List;

/** Raw extraction output; never committed to the graph directly. */
public record ExtractionResult(
        List<PreEntity> preEntities,
        List<RelationCandidate> relations,
        LlmUsage llmUsage,
        List<QuarantinedLine> quarantined,
        ExtractionIntegrity integrity) {

    public ExtractionResult(List<PreEntity> preEntities, List<RelationCandidate> relations) {
        this(preEntities, relations, LlmUsage.NONE, List.of(), ExtractionIntegrity.CLEAN);
    }

    public ExtractionResult(List<PreEntity> preEntities, List<RelationCandidate> relations,
                            LlmUsage llmUsage) {
        this(preEntities, relations, llmUsage, List.of(), ExtractionIntegrity.CLEAN);
    }

    public ExtractionResult(List<PreEntity> preEntities, List<RelationCandidate> relations,
                            LlmUsage llmUsage, List<QuarantinedLine> quarantined) {
        this(preEntities, relations, llmUsage, quarantined, ExtractionIntegrity.CLEAN);
    }

    public static ExtractionResult empty() {
        return new ExtractionResult(List.of(), List.of(), LlmUsage.NONE, List.of(),
                ExtractionIntegrity.CLEAN);
    }
}

package org.synanton.extraction.engine;

import java.util.List;

/** Raw extraction output; never committed to the graph directly. */
public record ExtractionResult(
        List<PreEntity> preEntities,
        List<RelationCandidate> relations,
        LlmUsage llmUsage,
        List<QuarantinedLine> quarantined,
        ExtractionIntegrity integrity,
        LlmProvenance provenance) {

    public ExtractionResult(List<PreEntity> preEntities, List<RelationCandidate> relations) {
        this(preEntities, relations, LlmUsage.NONE, List.of(), ExtractionIntegrity.CLEAN,
                LlmProvenance.NONE);
    }

    public ExtractionResult(List<PreEntity> preEntities, List<RelationCandidate> relations,
                            LlmUsage llmUsage) {
        this(preEntities, relations, llmUsage, List.of(), ExtractionIntegrity.CLEAN,
                LlmProvenance.NONE);
    }

    public ExtractionResult(List<PreEntity> preEntities, List<RelationCandidate> relations,
                            LlmUsage llmUsage, List<QuarantinedLine> quarantined) {
        this(preEntities, relations, llmUsage, quarantined, ExtractionIntegrity.CLEAN,
                LlmProvenance.NONE);
    }

    public ExtractionResult(List<PreEntity> preEntities, List<RelationCandidate> relations,
                            LlmUsage llmUsage, List<QuarantinedLine> quarantined,
                            ExtractionIntegrity integrity) {
        this(preEntities, relations, llmUsage, quarantined, integrity, LlmProvenance.NONE);
    }

    public static ExtractionResult empty() {
        return new ExtractionResult(List.of(), List.of(), LlmUsage.NONE, List.of(),
                ExtractionIntegrity.CLEAN, LlmProvenance.NONE);
    }
}

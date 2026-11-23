package org.synanton.extraction.engine.provenance;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;

/** Cached candidates retain the generation that originally produced them. */
public record CachedExtraction(
        UUID generationId,
        List<PreEntity> preEntities,
        List<RelationCandidate> relations) {

    public CachedExtraction {
        Objects.requireNonNull(generationId, "generationId");
        preEntities = List.copyOf(preEntities);
        relations = List.copyOf(relations);
    }
}

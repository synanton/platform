package org.synanton.relix.api.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** Document-scoped extraction graph over the existing extracted-mention storage. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentGraphResponse(
        String documentId,
        String sourceVersionId,
        List<Node> nodes,
        List<Edge> edges,
        boolean truncated) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Node(
            String mentionId,
            String surfaceForm,
            String proposedType,
            String chunkId,
            int chunkOffsetStart,
            int chunkOffsetEnd,
            int documentOffsetStart,
            int documentOffsetEnd,
            String resolutionState,
            String resolvedEntityId,
            String extractionGeneration) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Edge(
            String relationId,
            String subjectMentionId,
            String objectMentionId,
            String predicate,
            String evidenceChunkId,
            int evidenceChunkOffsetStart,
            int evidenceChunkOffsetEnd,
            String promotionState,
            String canonicalRelationId,
            long sourceGeneration,
            String extractionGeneration) {}
}

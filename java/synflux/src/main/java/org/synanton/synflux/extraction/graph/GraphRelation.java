package org.synanton.synflux.extraction.graph;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

/** Wire shape for {@code POST /graph/relations} (matches Relix's ExtractedRelation). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GraphRelation(
        String relationId,
        String tenantId,
        String sourceVersionId,
        String predicate,
        String subjectMentionId,
        String objectMentionId,
        String evidenceChunkId,
        int evidenceChunkOffsetStart,
        int evidenceChunkOffsetEnd,
        int evidenceDocumentOffsetStart,
        int evidenceDocumentOffsetEnd,
        Instant validatedAt,
        String sourceId,
        String revisionId,
        long generation,
        String extractionRunId) {

    public GraphRelation(String relationId, String tenantId, String sourceVersionId,
                         String predicate, String subjectMentionId, String objectMentionId,
                         String evidenceChunkId, int evidenceChunkOffsetStart,
                         int evidenceChunkOffsetEnd, int evidenceDocumentOffsetStart,
                         int evidenceDocumentOffsetEnd, Instant validatedAt) {
        this(relationId, tenantId, sourceVersionId, predicate, subjectMentionId, objectMentionId,
                evidenceChunkId, evidenceChunkOffsetStart, evidenceChunkOffsetEnd,
                evidenceDocumentOffsetStart, evidenceDocumentOffsetEnd, validatedAt,
                null, sourceVersionId, 0, null);
    }
}

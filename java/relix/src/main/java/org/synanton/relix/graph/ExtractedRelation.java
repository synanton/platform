package org.synanton.relix.graph;

import java.time.Instant;

/** A validated candidate relation between two extracted mentions (never a canonical entity edge). */
public record ExtractedRelation(
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

    public ExtractedRelation(String relationId, String tenantId, String sourceVersionId,
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

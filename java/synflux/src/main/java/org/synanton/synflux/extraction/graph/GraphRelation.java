package org.synanton.synflux.extraction.graph;

import java.time.Instant;

/** Wire shape for {@code POST /graph/relations} (matches Relix's ExtractedRelation). */
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
        Instant validatedAt) {}

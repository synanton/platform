package org.synanton.common.resolution;

/** Persisted extracted relation/provenance read from the Relix projection. */
public record ExtractedRelationView(
        String relationId,
        String tenantId,
        String sourceVersionId,
        String subjectMentionId,
        String objectMentionId,
        String predicate,
        String evidenceChunkId,
        int evidenceChunkOffsetStart,
        int evidenceChunkOffsetEnd) {}

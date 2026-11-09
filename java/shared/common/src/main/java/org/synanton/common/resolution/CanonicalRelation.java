package org.synanton.common.resolution;

/** A VALID canonical relation assertion eligible for promotion (4c.3). */
public record CanonicalRelation(
        String tenantId,
        String sourceVersionId,
        String extractedRelationId,
        String subjectMentionId,
        String subjectEntityId,
        String objectMentionId,
        String objectEntityId,
        String predicate,
        String ontologyVersion,
        String evidenceChunkId,
        int evidenceChunkOffsetStart,
        int evidenceChunkOffsetEnd) {}

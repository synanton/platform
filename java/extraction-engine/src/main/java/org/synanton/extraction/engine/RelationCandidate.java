package org.synanton.extraction.engine;

/** A candidate relation between two pre-entities, with its evidence span. */
public record RelationCandidate(
        String subjectMentionId,
        String predicate,
        String objectMentionId,
        String evidenceChunkId,
        int evidenceCharStart,
        int evidenceCharEnd) {}

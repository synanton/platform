package org.synanton.common.resolution;

import com.fasterxml.jackson.annotation.JsonAlias;

/** Persisted extracted relation/provenance read from the Relix projection. */
public record ExtractedRelationView(
        @JsonAlias("relation_id") String relationId,
        @JsonAlias("tenant_id") String tenantId,
        @JsonAlias("source_version_id") String sourceVersionId,
        @JsonAlias("subject_mention_id") String subjectMentionId,
        @JsonAlias("object_mention_id") String objectMentionId,
        String predicate,
        @JsonAlias("evidence_chunk_id") String evidenceChunkId,
        @JsonAlias("evidence_chunk_offset_start") int evidenceChunkOffsetStart,
        @JsonAlias("evidence_chunk_offset_end") int evidenceChunkOffsetEnd,
        @JsonAlias("source_id") String sourceId,
        @JsonAlias("revision_id") String revisionId,
        @JsonAlias("generation") long generation,
        @JsonAlias("extraction_run_id") String extractionRunId) {

    public ExtractedRelationView(String relationId, String tenantId, String sourceVersionId,
                                 String subjectMentionId, String objectMentionId, String predicate,
                                 String evidenceChunkId, int evidenceChunkOffsetStart,
                                 int evidenceChunkOffsetEnd) {
        this(relationId, tenantId, sourceVersionId, subjectMentionId, objectMentionId, predicate,
                evidenceChunkId, evidenceChunkOffsetStart, evidenceChunkOffsetEnd,
                null, sourceVersionId, 0, null);
    }
}

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
        @JsonAlias("evidence_chunk_offset_end") int evidenceChunkOffsetEnd) {}

package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Durable canonical-relation promotion decision (PR #4a). */
public record RelationPromotionResultRow(
        String tenantId,
        UUID sourceVersionId,
        String relationId,
        String subjectMentionId,
        String objectMentionId,
        String predicate,
        String promotionStatus,
        String canonicalSubjectId,
        String canonicalObjectId,
        String reason,
        String evidenceJson,
        String strategyVersion,
        String ontologyVersion,
        Instant decidedAt,
        Instant supersededAt,
        String sourceId,
        UUID revisionId,
        long generation,
        String extractionRunId,
        String identityStatus) {

    public RelationPromotionResultRow(String tenantId, UUID sourceVersionId, String relationId,
                                      String subjectMentionId, String objectMentionId,
                                      String predicate, String promotionStatus,
                                      String canonicalSubjectId, String canonicalObjectId,
                                      String reason, String evidenceJson, String strategyVersion,
                                      String ontologyVersion, Instant decidedAt,
                                      Instant supersededAt) {
        this(tenantId, sourceVersionId, relationId, subjectMentionId, objectMentionId,
                predicate, promotionStatus, canonicalSubjectId, canonicalObjectId, reason,
                evidenceJson, strategyVersion, ontologyVersion, decidedAt, supersededAt,
                null, null, 0, null, "LEGACY_UNVERSIONED");
    }
}

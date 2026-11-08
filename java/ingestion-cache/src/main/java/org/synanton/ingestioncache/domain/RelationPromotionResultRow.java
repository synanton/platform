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
        Instant supersededAt) {}

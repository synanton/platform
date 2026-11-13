package org.synanton.relix.graph;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.resolution.PromotionResult;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.common.resolution.SourceRevisionIdentity;
import org.synanton.common.resolution.UnauthorizedGenerationException;
import org.synanton.relix.identity.SourceFenceKey;
import org.synanton.relix.identity.SourceGenerationAuthority;
import org.synanton.relix.identity.GraphIdentityResolver;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PR #4c.3: promotion consumes only VALID validation rows, then delegates the
 * atomic graph transaction (which re-verifies active resolutions). No consumer.
 */
@Component
public final class RelixCanonicalRelationPromoter implements CanonicalRelationPromotionPort {

    private final IngestionCacheClient cacheClient;
    private final GraphWritePort writePort;
    private final GraphIdentityResolver identityResolver;
    private final SourceGenerationAuthority generationAuthority;

    public RelixCanonicalRelationPromoter(IngestionCacheClient cacheClient,
                                           GraphWritePort writePort,
                                           GraphIdentityResolver identityResolver) {
        this(cacheClient, writePort, identityResolver, (SourceGenerationAuthority) null);
    }

    @Autowired
    public RelixCanonicalRelationPromoter(IngestionCacheClient cacheClient,
                                           GraphWritePort writePort,
                                           GraphIdentityResolver identityResolver,
                                           ObjectProvider<SourceGenerationAuthority> authorityProvider) {
        this(cacheClient, writePort, identityResolver, authorityProvider.getIfAvailable());
    }

    public RelixCanonicalRelationPromoter(IngestionCacheClient cacheClient,
                                            GraphWritePort writePort,
                                            GraphIdentityResolver identityResolver,
                                            SourceGenerationAuthority generationAuthority) {
        this.cacheClient = cacheClient;
        this.writePort = writePort;
        this.identityResolver = identityResolver;
        this.generationAuthority = generationAuthority;
    }

    @Override
    public PromotionResult promoteRelation(CanonicalRelation relation) {
        if (relation == null || relation.tenantId() == null || relation.sourceVersionId() == null) {
            throw new IllegalArgumentException("tenant and source version are required");
        }
        UUID version;
        try {
            version = UUID.fromString(relation.sourceVersionId());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("source version must be a UUID", e);
        }
        RelationPromotionResultRow validation = cacheClient
                .listRelationPromotionResults(relation.tenantId(), version).stream()
                .filter(row -> Objects.equals(row.relationId(), relation.extractedRelationId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No relation validation result for " + relation.extractedRelationId()));
        if (!"VALID".equals(validation.promotionStatus())) {
            throw new IllegalStateException("Relation validation is not VALID: "
                    + validation.promotionStatus());
        }
        if (!Objects.equals(validation.canonicalSubjectId(), relation.subjectEntityId())
                || !Objects.equals(validation.canonicalObjectId(), relation.objectEntityId())
                || !Objects.equals(validation.subjectMentionId(), relation.subjectMentionId())
                || !Objects.equals(validation.objectMentionId(), relation.objectMentionId())
                || !PromotionIds.normalizePredicate(validation.predicate())
                .equals(PromotionIds.normalizePredicate(relation.predicate()))) {
            throw new IllegalStateException("VALID validation row does not match the promotion request");
        }
        if (relation.sourceId() == null || relation.sourceId().isBlank()
                || relation.generation() < 1) {
            throw new UnauthorizedGenerationException(
                    "Promotion lacks a CURRENT source revision identity");
        }
        if (generationAuthority == null) {
            throw new UnauthorizedGenerationException("Source generation authority is unavailable");
        }
        Optional<Long> accepted;
        try {
            accepted = generationAuthority.acceptedGeneration(
                    new SourceFenceKey(relation.tenantId(), relation.sourceId()));
        } catch (RuntimeException e) {
            throw new RetryableProcessingException("Source generation authority unavailable", e);
        }
        if (accepted.isEmpty() || accepted.get() != relation.generation()) {
            throw new UnauthorizedGenerationException(
                    "Cassandra no longer accepts promotion generation " + relation.generation());
        }
        GraphIdentity identity = identityResolver.resolveCanonical(relation.tenantId());
        return writePort.promoteRelation(identity, relation,
                new SourceRevisionIdentity(relation.tenantId(), relation.sourceId(),
                        relation.generation()));
    }
}

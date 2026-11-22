package org.synanton.relix.graph;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(RelixCanonicalRelationPromoter.class);
    private static final Set<String> CONTRADICTION_CODES = Set.of(
            "domain_mismatch", "range_mismatch", "negative_polarity");
    private static final Set<String> FAILED_ASSERTION_CODES = Set.of(
            "unknown_predicate", "unknown_subject_type", "unknown_object_type",
            "missing_subject_type", "missing_object_type", "missing_predicate");

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
        boolean invalid = "INVALID".equals(validation.promotionStatus());
        if (!"VALID".equals(validation.promotionStatus()) && !invalid) {
            throw new IllegalStateException("Relation validation is neither VALID nor INVALID: "
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
        SourceRevisionIdentity sourceRevision = new SourceRevisionIdentity(
                relation.tenantId(), relation.sourceId(), relation.generation());
        if (invalid) {
            if (!isContradiction(validation.reason())) {
                log.info("Relation promotion {} classified as failed assertion ({}); "
                                + "no contradiction evidence written",
                        relation.extractedRelationId(), validation.reason());
                return new PromotionResult(0, 0, 0);
            }
            if (relation.subjectEntityId() == null || relation.objectEntityId() == null) {
                return new PromotionResult(0, 0, 0);
            }
            return writePort.recordContradiction(identity, relation, sourceRevision);
        }
        return writePort.promoteRelation(identity, relation, sourceRevision);
    }

    /**
     * A contradiction is an ontology-invalid assertion with known predicate/types
     * whose shape the ontology rejects (domain/range mismatch). Missing evidence,
     * unresolved endpoints, unknown predicates/types, and unclassified reasons are
     * failed assertions and must not produce CONTRADICTS evidence.
     */
    static boolean isContradiction(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        int separator = reason.indexOf(':');
        String reasonCode = (separator < 0 ? reason : reason.substring(0, separator))
                .trim().toUpperCase(Locale.ROOT);
        if (!"ONTOLOGY_INVALID".equals(reasonCode)) {
            return false;
        }
        if (separator < 0) {
            return false;
        }
        Set<String> codes = Arrays.stream(reason.substring(separator + 1).split(","))
                .map(String::trim)
                .map(code -> code.toLowerCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        if (codes.stream().anyMatch(FAILED_ASSERTION_CODES::contains)) {
            return false;
        }
        return codes.stream().anyMatch(CONTRADICTION_CODES::contains);
    }
}

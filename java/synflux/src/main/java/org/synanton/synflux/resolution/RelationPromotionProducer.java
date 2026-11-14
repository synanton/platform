package org.synanton.synflux.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.common.resolution.ActiveResolution;
import org.synanton.common.resolution.ExtractedMentionGraphReadPort;
import org.synanton.common.resolution.ExtractedRelationView;
import org.synanton.common.resolution.RelationValidationResult;
import org.synanton.common.resolution.ResolutionGraphLookup;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.common.resolution.StaleOwnershipException;
import org.synanton.common.resolution.ValidationViolation;
import org.synanton.ingestioncache.claim.PromotionOwnershipStore;
import org.synanton.ingestioncache.claim.PromotionOwnershipStore.OwnershipToken;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;

/**
 * RAKG #583: after resolution completes, validate each extracted relation and
 * materialize promotion work. The producer is the sole writer of promotion
 * result rows and owns each source version until its promotion set is closed.
 */
@Component
public final class RelationPromotionProducer {

    private static final Logger log = LoggerFactory.getLogger(RelationPromotionProducer.class);

    private final ResolutionGraphLookup graphLookup;
    private final ExtractedMentionGraphReadPort extractedReadPort;
    private final CanonicalRelationValidator validator;
    private final IngestionCacheClient cacheClient;
    private final PromotionOwnershipStore ownershipStore;
    private final Clock clock;
    private final boolean enabled;
    private final String tenant;
    private final String ontologyVersion;
    private final String producerId;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public RelationPromotionProducer(
            ResolutionGraphLookup graphLookup,
            ExtractedMentionGraphReadPort extractedReadPort,
            CanonicalRelationValidator validator,
            IngestionCacheClient cacheClient,
            PromotionOwnershipStore ownershipStore,
            Clock clock,
            @Value("${PROMOTION_PRODUCER_ENABLED:false}") boolean enabled,
            @Value("${PROMOTION_PRODUCER_TENANT:local-real-demo}") String tenant,
            @Value("${PROMOTION_PRODUCER_ONTOLOGY_VERSION:active}") String ontologyVersion) {
        this(graphLookup, extractedReadPort, validator, cacheClient, ownershipStore, clock,
                enabled, tenant, ontologyVersion,
                "synflux-promotion-" + UUID.randomUUID());
    }

    RelationPromotionProducer(
            ResolutionGraphLookup graphLookup,
            ExtractedMentionGraphReadPort extractedReadPort,
            CanonicalRelationValidator validator,
            IngestionCacheClient cacheClient,
            PromotionOwnershipStore ownershipStore,
            Clock clock,
            boolean enabled,
            String tenant,
            String ontologyVersion,
            String producerId) {
        this.graphLookup = graphLookup;
        this.extractedReadPort = extractedReadPort;
        this.validator = validator;
        this.cacheClient = cacheClient;
        this.ownershipStore = ownershipStore;
        this.clock = clock;
        this.enabled = enabled;
        this.tenant = tenant;
        this.ontologyVersion = ontologyVersion;
        this.producerId = producerId;
    }

    public RelationPromotionProducer(
            ResolutionGraphLookup graphLookup,
            ExtractedMentionGraphReadPort extractedReadPort,
            CanonicalRelationValidator validator,
            IngestionCacheClient cacheClient,
            @Value("${PROMOTION_PRODUCER_ENABLED:false}") boolean enabled,
            @Value("${PROMOTION_PRODUCER_TENANT:local-real-demo}") String tenant,
            @Value("${PROMOTION_PRODUCER_ONTOLOGY_VERSION:active}") String ontologyVersion) {
        this(graphLookup, extractedReadPort, validator, cacheClient, null, Clock.systemUTC(),
                enabled, tenant, ontologyVersion);
    }

    @Scheduled(fixedDelayString = "${PROMOTION_TAKEOVER_SWEEP_INTERVAL_MS:60000}")
    public void recoverStaleOwnership() {
        if (!enabled || tenant == null || tenant.isBlank() || ownershipStore == null) {
            return;
        }
        int won = ownershipStore.takeoverStale(producerId,
                clock.instant().minusSeconds(900), 100);
        if (won > 0) {
            log.warn("Promotion ownership takeover reclaimed {} stale revisions", won);
        }
    }

    @Scheduled(fixedDelayString = "${PROMOTION_PRODUCER_POLL_DELAY_MS:5000}")
    public void poll() {
        if (!enabled || tenant == null || tenant.isBlank()) {
            return;
        }
        List<SourceVersionRow> candidates;
        try {
            candidates = cacheClient.listMentionsCommittedSourceVersions(tenant, 100);
        } catch (Exception e) {
            log.warn("Relation promotion producer could not list MENTIONS_COMMITTED versions: {}",
                    e.toString());
            return;
        }
        for (SourceVersionRow candidate : candidates) {
            processVersion(candidate.versionId());
        }
    }

    private void processVersion(UUID version) {
        if (ownershipStore == null) {
            log.warn("Relation promotion producer has no ownership store; skipping version={}", version);
            return;
        }
        // Acquire before scanning so a zero-row decision is serialized against other producers.
        Optional<OwnershipToken> acquired = acquire(version);
        if (acquired.isEmpty()) {
            return;
        }
        OwnershipToken token = acquired.get();
        List<ExtractedRelationView> relations;
        try {
            relations = extractedReadPort.listExtractedRelations(tenant, version.toString());
        } catch (Exception e) {
            log.warn("Relation promotion producer could not list relations version={}: {}",
                    version, e.toString());
            return;
        }
        Set<String> existing = existingRelationIds(version);
        if (existing == null) {
            return;
        }
        boolean canClose = true;
        for (ExtractedRelationView relation : relations) {
            if (existing.contains(relation.relationId())) {
                continue;
            }
            if (!ready(relation)) {
                canClose = false;
                continue;
            }
            try {
                RelationValidationResult result = validator.validate(tenant,
                        ontologyVersion, relation.relationId());
                ownershipStore.insertPromotionResult(buildRow(relation, result), token);
                existing.add(relation.relationId());
            } catch (StaleOwnershipException e) {
                log.warn("Relation promotion ownership lost version={} producer={} generation={}; "
                                + "aborting this version", version, token.producerId(),
                        token.generation());
                return;
            } catch (Exception e) {
                canClose = false;
                log.warn("Relation promotion validation deferred relation={}: {}",
                        relation.relationId(), e.toString());
            }
        }
        if (!canClose) {
            return;
        }
        try {
            ownershipStore.closeSet(tenant, version, token);
        } catch (StaleOwnershipException e) {
            log.warn("Relation promotion close lost ownership version={} producer={} generation={}",
                    version, token.producerId(), token.generation());
        }
    }

    private Optional<OwnershipToken> acquire(UUID version) {
        try {
            return ownershipStore.acquire(tenant, version, producerId);
        } catch (Exception e) {
            log.warn("Relation promotion producer could not acquire ownership version={}: {}",
                    version, e.toString());
            return Optional.empty();
        }
    }

    private RelationPromotionResultRow buildRow(ExtractedRelationView view,
                                                RelationValidationResult result) {
        UUID version = version(view);
        if (version == null) {
            throw new RetryableProcessingException("Invalid source version on extracted relation");
        }
        UUID revisionId = revisionId(view, version);
        String identityStatus = view.sourceId() == null || view.generation() < 1
                ? "LEGACY_UNVERSIONED" : "CURRENT";
        return new RelationPromotionResultRow(
                tenant, version, view.relationId(), view.subjectMentionId(),
                view.objectMentionId(), view.predicate(), result.outcome().name(),
                result.subjectEntityId(), result.objectEntityId(),
                reason(result), evidenceJson(view, result),
                DefaultCanonicalRelationValidator.STRATEGY_VERSION, result.ontologyVersion(),
                Instant.now(clock), null, view.sourceId(), revisionId, view.generation(),
                view.extractionRunId(), identityStatus);
    }

    private String evidenceJson(ExtractedRelationView view, RelationValidationResult result) {
        try {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("view", view);
            evidence.put("violations", result.violations());
            return mapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new RetryableProcessingException("Could not serialize validation evidence", e);
        }
    }

    private static UUID revisionId(ExtractedRelationView view, UUID version) {
        if (view.revisionId() == null) {
            return version;
        }
        try {
            return UUID.fromString(view.revisionId());
        } catch (IllegalArgumentException e) {
            throw new RetryableProcessingException("Invalid revision id on extracted relation", e);
        }
    }

    private static UUID version(ExtractedRelationView relation) {
        if (relation.sourceVersionId() == null) {
            return null;
        }
        try {
            return UUID.fromString(relation.sourceVersionId());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String reason(RelationValidationResult result) {
        if (result.violations().isEmpty()) {
            return result.reasonCode();
        }
        return result.reasonCode() + ":" + String.join(",",
                result.violations().stream().map(ValidationViolation::code).toList());
    }

    private boolean ready(ExtractedRelationView relation) {
        List<ActiveResolution> active;
        try {
            active = graphLookup.getActiveResolutions(tenant,
                    List.of(relation.subjectMentionId(), relation.objectMentionId()));
        } catch (Exception e) {
            return false;
        }
        long subjects = active.stream()
                .filter(row -> relation.subjectMentionId().equals(row.mentionId())).count();
        long objects = active.stream()
                .filter(row -> relation.objectMentionId().equals(row.mentionId())).count();
        return subjects == 1 && objects == 1;
    }

    private Set<String> existingRelationIds(UUID version) {
        Set<String> ids = new HashSet<>();
        try {
            for (RelationPromotionResultRow row : cacheClient.listRelationPromotionResults(tenant, version)) {
                ids.add(row.relationId());
            }
        } catch (Exception e) {
            log.warn("Relation promotion producer could not read existing results version={}: {}",
                    version, e.toString());
            return null;
        }
        return ids;
    }
}

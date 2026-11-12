package org.synanton.synflux.resolution;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.common.resolution.ActiveResolution;
import org.synanton.common.resolution.ExtractedMentionGraphReadPort;
import org.synanton.common.resolution.ExtractedRelationView;
import org.synanton.common.resolution.ResolutionGraphLookup;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;

/**
 * RAKG #583: after resolution completes, validate each extracted relation and
 * materialize promotion work. The producer is idempotent and never overwrites
 * an existing promotion result, so a promotion claim cannot be clobbered.
 */
@Component
public final class RelationPromotionProducer {

    private static final Logger log = LoggerFactory.getLogger(RelationPromotionProducer.class);

    private final ResolutionGraphLookup graphLookup;
    private final ExtractedMentionGraphReadPort extractedReadPort;
    private final CanonicalRelationValidator validator;
    private final IngestionCacheClient cacheClient;
    private final boolean enabled;
    private final String tenant;
    private final String ontologyVersion;

    public RelationPromotionProducer(
            ResolutionGraphLookup graphLookup,
            ExtractedMentionGraphReadPort extractedReadPort,
            CanonicalRelationValidator validator,
            IngestionCacheClient cacheClient,
            @Value("${PROMOTION_PRODUCER_ENABLED:false}") boolean enabled,
            @Value("${PROMOTION_PRODUCER_TENANT:local-real-demo}") String tenant,
            @Value("${PROMOTION_PRODUCER_ONTOLOGY_VERSION:active}") String ontologyVersion) {
        this.graphLookup = graphLookup;
        this.extractedReadPort = extractedReadPort;
        this.validator = validator;
        this.cacheClient = cacheClient;
        this.enabled = enabled;
        this.tenant = tenant;
        this.ontologyVersion = ontologyVersion;
    }

    @Scheduled(fixedDelayString = "${PROMOTION_PRODUCER_POLL_DELAY_MS:5000}")
    public void poll() {
        if (!enabled || tenant == null || tenant.isBlank()) {
            return;
        }
        List<ExtractedRelationView> relations;
        try {
            relations = extractedReadPort.listExtractedRelations(tenant, null);
        } catch (Exception e) {
            log.warn("Relation promotion producer could not list extracted relations: {}", e.toString());
            return;
        }
        Map<UUID, Set<String>> existing = new HashMap<>();
        for (ExtractedRelationView relation : relations) {
            UUID version;
            try {
                version = UUID.fromString(relation.sourceVersionId());
            } catch (IllegalArgumentException e) {
                continue;
            }
            Set<String> relationIds = existing.computeIfAbsent(version,
                    value -> existingRelationIds(version));
            if (relationIds == null || relationIds.contains(relation.relationId())) {
                continue;
            }
            if (!ready(relation)) {
                continue;
            }
            try {
                validator.validate(tenant, ontologyVersion, relation.relationId());
                relationIds.add(relation.relationId());
            } catch (Exception e) {
                log.warn("Relation promotion validation deferred relation={}: {}",
                        relation.relationId(), e.toString());
            }
        }
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

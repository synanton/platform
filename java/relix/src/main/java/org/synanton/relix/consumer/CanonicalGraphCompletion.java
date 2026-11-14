package org.synanton.relix.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.activation.ActivationRecord;
import org.synanton.ingestioncache.activation.ActivationState;
import org.synanton.ingestioncache.activation.SourceActivationStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.ingestioncache.domain.RelationPromotionWorkRow;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;

/**
 * Canonical graph completion owned by promotion. GRAPH_COMMITTED means every
 * relation promotion row for the source version is terminal (or there were no
 * rows); the durable ActivationRequested event is written in the same Cassandra
 * LWT that flips source_versions.graph_status.
 */
@Component
public final class CanonicalGraphCompletion {

    private static final Logger log = LoggerFactory.getLogger(CanonicalGraphCompletion.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Only a successfully promoted row or an expected validation rejection authorizes
    // canonical completion. FAILED/SUPERSEDED are terminal for the worker but must
    // block completion; otherwise a regression could retire valid prior knowledge.
    private static final Set<String> COMPLETION_AUTHORIZING_STATES = Set.of("COMPLETED");

    private final IngestionCacheClient cache;
    private final SourceActivationStore activations;
    private final Clock clock;

    public CanonicalGraphCompletion(IngestionCacheClient cache,
                                    SourceActivationStore activations,
                                    Clock clock) {
        this.cache = cache;
        this.activations = activations;
        this.clock = clock;
    }

    /** Completes for a promotion row only when every row for its version is terminal. */
    public boolean completeIfPromotionTerminal(RelationPromotionResultRow row) {
        if (!promotionsTerminal(row.tenantId(), row.sourceVersionId())) {
            return false;
        }
        SourceVersionRow version = resolveVersion(row);
        if (version == null) {
            log.warn("Canonical completion could not resolve source version tenant={} version={}",
                    row.tenantId(), row.sourceVersionId());
            return false;
        }
        return complete(version);
    }

    /** Completes for a scanned source version only when all promotion rows are terminal. */
    public boolean completeIfPromotionTerminal(SourceVersionRow version) {
        if (!promotionsTerminal(version.tenantId(), version.versionId())) {
            return false;
        }
        return complete(version);
    }

    public boolean promotionsTerminal(String tenantId, UUID sourceVersionId) {
        List<RelationPromotionWorkRow> rows = cache.listRelationPromotionWorkRows(
                tenantId, sourceVersionId);
        return rows.stream().allMatch(CanonicalGraphCompletion::completionAuthorizing);
    }

    public static boolean completionAuthorizing(RelationPromotionWorkRow work) {
        String claimStatus = work.claimStatus();
        if (claimStatus == null) {
            return work.result().promotionStatus() != null
                    && !"VALID".equals(work.result().promotionStatus());
        }
        return COMPLETION_AUTHORIZING_STATES.contains(claimStatus);
    }

    private boolean complete(SourceVersionRow version) {
        UUID eventId = activationEventId(version);
        Instant now = clock.instant();
        String payload = activationPayload(version, eventId);
        boolean written = cache.completeCanonicalGraphCommit(
                version.tenantId(), version.connectorId(), version.sourceId(), version.resourceId(),
                version.versionId(), eventId, "ActivationRequested", payload, now);
        if (written) {
            transitionActivation(version);
            return true;
        }
        boolean alreadyCommitted = cache.readGraphStatus(version.tenantId(), version.connectorId(),
                        version.sourceId(), version.resourceId(), version.versionId())
                .filter("GRAPH_COMMITTED"::equals)
                .isPresent();
        if (alreadyCommitted) {
            return transitionActivation(version);
        }
        return false;
    }

    private boolean transitionActivation(SourceVersionRow version) {
        Optional<ActivationRecord> maybeRecord = activations.read(
                version.tenantId(), version.sourceId(), version.generation());
        if (maybeRecord.isEmpty()) {
            return false;
        }
        ActivationRecord record = maybeRecord.get();
        if (record.state() == ActivationState.GRAPH_COMMITTED) {
            return true;
        }
        if (record.state() != ActivationState.MENTIONS_COMMITTED) {
            return false;
        }
        boolean moved = activations.transition(record.tenantId(), record.sourceId(),
                record.generation(), ActivationState.MENTIONS_COMMITTED, ActivationState.GRAPH_COMMITTED,
                new SourceActivationStore.ProjectionMarkers(record.generation(), "COMMITTED",
                        record.searchAppliedGeneration(), record.searchAppliedState()),
                null, true, clock.instant());
        if (!moved) {
            log.warn("Canonical completion LWT succeeded but activation transition was lost "
                            + "tenant={} source={} generation={}",
                    record.tenantId(), record.sourceId(), record.generation());
        }
        return moved;
    }

    private SourceVersionRow resolveVersion(RelationPromotionResultRow row) {
        if (row.sourceId() != null && row.generation() > 0) {
            Optional<ActivationRecord> record = activations.read(
                    row.tenantId(), row.sourceId(), row.generation());
            if (record.isPresent()
                    && row.sourceVersionId().equals(record.get().revisionId())) {
                ActivationRecord activation = record.get();
                return new SourceVersionRow(activation.tenantId(), activation.connectorId(),
                        activation.sourceId(), activation.resourceId(), activation.revisionId(),
                        activation.contentHash(), null, activation.createdAt(), null, null, "",
                        activation.generation(), activation.contentHash(), activation.previousRevisionId());
            }
        }
        for (SourceRow source : cache.listSources(row.tenantId())) {
            if (!source.sourceId().equals(row.sourceId())) {
                continue;
            }
            for (SourceIdentityRow identity : cache.listSourceIdentities(
                    row.tenantId(), source.sourceId(), source.connectorId())) {
                for (SourceVersionRow version : cache.listSourceVersions(row.tenantId(),
                        identity.connectorId(), identity.sourceId(), identity.resourceId())) {
                    if (version.versionId().equals(row.sourceVersionId())) {
                        return version;
                    }
                }
            }
        }
        return null;
    }

    static UUID activationEventId(SourceVersionRow version) {
        String identity = version.tenantId() + "|" + version.sourceId() + "|"
                + version.versionId() + "|" + version.generation();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    static String activationPayload(SourceVersionRow version, UUID eventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", eventId);
        payload.put("event_type", "ActivationRequested");
        payload.put("tenant_id", version.tenantId());
        payload.put("source_id", version.sourceId());
        payload.put("revision_id", version.versionId());
        payload.put("generation", version.generation());
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"event_type\":\"ActivationRequested\",\"tenant_id\":\""
                    + version.tenantId() + "\",\"source_id\":\"" + version.sourceId()
                    + "\",\"revision_id\":\"" + version.versionId() + "\",\"generation\":"
                    + version.generation() + "}";
        }
    }
}

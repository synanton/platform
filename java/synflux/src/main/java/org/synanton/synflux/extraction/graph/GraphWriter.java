package org.synanton.synflux.extraction.graph;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ExtractionRequestRow;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.synanton.synflux.config.ExtractionGraphProperties;

/**
 * RAKG PR #3 orchestrator: deterministic mention ids, Syntology relation
 * validation, isolated Relix writes, and graph_status/counters. It never creates
 * canonical entities or RESOLVES_TO edges (PR #4).
 */
public final class GraphWriter {

    private static final Logger log = LoggerFactory.getLogger(GraphWriter.class);

    private final IngestionCacheClient cacheClient;
    private final RelationValidator validator;
    private final GraphWriteClient graphClient;
    private final ExtractionGraphProperties properties;
    private final Clock clock;

    public GraphWriter(IngestionCacheClient cacheClient, RelationValidator validator,
                       GraphWriteClient graphClient, ExtractionGraphProperties properties, Clock clock) {
        this.cacheClient = cacheClient;
        this.validator = validator;
        this.graphClient = graphClient;
        this.properties = properties;
        this.clock = clock;
    }

    public void commit(ExtractionRequestRow request, List<ChunkRow> chunks,
                       Map<Integer, ExtractionResult> resultsByOrdinal) {
        commit(request, chunks, resultsByOrdinal, 0);
    }

    public void commit(ExtractionRequestRow request, List<ChunkRow> chunks,
                       Map<Integer, ExtractionResult> resultsByOrdinal, int graphAttempt) {
        if (!properties.enabled()) {
            return;
        }
        if (!cacheClient.claimGraphCommit(request)) {
            return;
        }
        Map<String, Integer> counters = counters(0, 0, 0, 0);
        try {
            Map<Integer, ChunkRow> chunkByOrdinal = new LinkedHashMap<>();
            for (ChunkRow chunk : chunks) {
                chunkByOrdinal.put(chunk.chunkOrdinal(), chunk);
            }
            Instant now = clock.instant();
            List<GraphMention> mentions = new ArrayList<>();
            Map<String, GraphMention> uniqueMentions = new LinkedHashMap<>();
            Map<String, GraphMention> byRawMention = new LinkedHashMap<>();
            for (Map.Entry<Integer, ExtractionResult> entry : resultsByOrdinal.entrySet()) {
                int ordinal = entry.getKey();
                ChunkRow chunk = chunkByOrdinal.get(ordinal);
                if (chunk == null) {
                    continue;
                }
                String chunkId = request.contentRefId() + ":" + ordinal;
                for (PreEntity entity : entry.getValue().preEntities()) {
                    String mentionId = StableIds.mentionId(request.tenantId(),
                            request.versionId().toString(), chunkId, entity.charStart(), entity.charEnd(),
                            entity.surfaceForm());
                    int documentStart = chunk.documentOffsetStart() >= 0
                            ? chunk.documentOffsetStart() + entity.charStart() : -1;
                    int documentEnd = chunk.documentOffsetStart() >= 0
                            ? chunk.documentOffsetStart() + entity.charEnd() : -1;
                    GraphMention mention = new GraphMention(mentionId, request.tenantId(),
                            request.versionId().toString(), request.contentRefId().toString(), chunkId,
                            entity.charStart(), entity.charEnd(), documentStart, documentEnd,
                            entity.surfaceForm(), entity.proposedType(), now);
                    uniqueMentions.putIfAbsent(mention.mentionId(), mention);
                    byRawMention.put(ordinal + "|" + entity.mentionId(), mention);
                }
            }
            mentions.addAll(uniqueMentions.values());
            counters.put("mentions_accepted", mentions.size());

            Map<String, GraphRelation> acceptedById = new LinkedHashMap<>();
            int rejected = 0;
            for (Map.Entry<Integer, ExtractionResult> entry : resultsByOrdinal.entrySet()) {
                int ordinal = entry.getKey();
                ChunkRow chunk = chunkByOrdinal.get(ordinal);
                if (chunk == null) {
                    continue;
                }
                for (RelationCandidate relation : entry.getValue().relations()) {
                    GraphMention subject = byRawMention.get(ordinal + "|" + relation.subjectMentionId());
                    GraphMention object = byRawMention.get(ordinal + "|" + relation.objectMentionId());
                    if (subject == null || object == null) {
                        rejected++;
                        continue;
                    }
                    RelationValidation validation = validator.validate(request.tenantId(),
                            subject.proposedType(), relation.predicate(), object.proposedType(),
                            properties.ontologyVersionOrDefault());
                    if (!validation.conformant()) {
                        rejected++;
                        log.debug("Relation rejected tenant={} predicate={} violations={}",
                                request.tenantId(), relation.predicate(), validation.violations());
                        continue;
                    }
                    String relationId = StableIds.relationId(request.tenantId(),
                            request.versionId().toString(), subject.mentionId(),
                            relation.predicate(), object.mentionId());
                    int evidenceDocumentStart = chunk.documentOffsetStart() >= 0
                            ? chunk.documentOffsetStart() + relation.evidenceCharStart() : -1;
                    int evidenceDocumentEnd = chunk.documentOffsetStart() >= 0
                            ? chunk.documentOffsetStart() + relation.evidenceCharEnd() : -1;
                    // Provenance is authoritative from the current TextSegment,
                    // never the model's candidate id.
                    String evidenceChunkId = request.contentRefId() + ":" + ordinal;
                    acceptedById.putIfAbsent(relationId, new GraphRelation(relationId, request.tenantId(),
                            request.versionId().toString(), relation.predicate(), subject.mentionId(),
                            object.mentionId(), evidenceChunkId,
                            relation.evidenceCharStart(), relation.evidenceCharEnd(),
                            evidenceDocumentStart, evidenceDocumentEnd, now,
                            request.sourceId(), request.versionId().toString(), request.generation(), null));
                }
            }
            List<GraphRelation> accepted = new ArrayList<>(acceptedById.values());
            counters.put("relations_accepted", accepted.size());
            counters.put("relations_rejected", rejected);

            if (!mentions.isEmpty()) {
                int persistedMentions = graphClient.upsertMentions(mentions);
                if (persistedMentions < mentions.size()) {
                    throw new IllegalStateException("Relix persisted " + persistedMentions
                            + " of " + mentions.size() + " extracted mentions");
                }
            }
            if (!accepted.isEmpty()) {
                int persistedRelations = graphClient.upsertRelations(accepted);
                if (persistedRelations < accepted.size()) {
                    throw new IllegalStateException("Relix persisted " + persistedRelations
                            + " of " + accepted.size() + " extracted relations");
                }
            }
            // Create idempotent resolution work only after the mention graph is durable.
            for (GraphMention mention : mentions) {
                int ordinal = ordinalFromChunkId(mention.chunkId());
                cacheClient.insertResolutionWork(new ResolutionWorkRow(
                        request.tenantId(), request.versionId(), mention.mentionId(),
                        request.connectorId(), request.sourceId(), request.resourceId(),
                        request.contentRefId(), ordinal, mention.chunkOffsetStart(),
                        mention.chunkOffsetEnd(), mention.documentOffsetStart(),
                        mention.documentOffsetEnd(), mention.surfaceForm(), mention.proposedType(),
                        "PENDING", null, null, 0, null, null, now, null, null, null));
            }
            String graphStatus = rejected > 0 ? "PARTIAL" : "GRAPH_COMMITTED";
            cacheClient.markGraphCommitted(request, graphStatus, counters, graphAttempt, clock.instant());
            log.info("Graph commit tenant={} version={} status={} mentions={} relations={} rejected={}",
                    request.tenantId(), request.versionId(), graphStatus, mentions.size(),
                    accepted.size(), rejected);
        } catch (Exception e) {
            int attempt = graphAttempt + 1;
            Instant nextAttemptAt = clock.instant().plusSeconds(backoffSeconds(attempt));
            try {
                cacheClient.markGraphFailed(request, "FAILED", counters, attempt, nextAttemptAt, clock.instant());
            } catch (Exception markFailure) {
                log.warn("Could not mark graph failure tenant={} version={}: {}",
                        request.tenantId(), request.versionId(), markFailure.toString());
            }
            log.warn("Graph commit failed tenant={} version={}: {}",
                    request.tenantId(), request.versionId(), e.toString());
        }
    }

    private static int ordinalFromChunkId(String chunkId) {
        if (chunkId == null) {
            return -1;
        }
        int separator = chunkId.lastIndexOf(':');
        if (separator < 0 || separator == chunkId.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(chunkId.substring(separator + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long backoffSeconds(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 5);
        return Math.min(300, 30 * multiplier);
    }

    private static Map<String, Integer> counters(int mentionsAccepted, int mentionsRejected,
                                                 int relationsAccepted, int relationsRejected) {
        Map<String, Integer> counters = new LinkedHashMap<>();
        counters.put("mentions_accepted", mentionsAccepted);
        counters.put("mentions_rejected", mentionsRejected);
        counters.put("relations_accepted", relationsAccepted);
        counters.put("relations_rejected", relationsRejected);
        return counters;
    }
}

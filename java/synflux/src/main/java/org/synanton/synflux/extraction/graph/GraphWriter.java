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
        if (!properties.enabled()) {
            return;
        }
        Map<String, Integer> counters = counters(0, 0, 0, 0);
        try {
            cacheClient.markGraphValidating(request);
            Map<Integer, ChunkRow> chunkByOrdinal = new LinkedHashMap<>();
            for (ChunkRow chunk : chunks) {
                chunkByOrdinal.put(chunk.chunkOrdinal(), chunk);
            }
            Instant now = clock.instant();
            List<GraphMention> mentions = new ArrayList<>();
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
                    mentions.add(mention);
                    byRawMention.put(ordinal + "|" + entity.mentionId(), mention);
                }
            }
            counters.put("mentions_accepted", mentions.size());

            List<GraphRelation> accepted = new ArrayList<>();
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
                    String evidenceChunkId = relation.evidenceChunkId() == null
                            || relation.evidenceChunkId().isBlank()
                            ? request.contentRefId() + ":" + ordinal
                            : relation.evidenceChunkId();
                    accepted.add(new GraphRelation(relationId, request.tenantId(),
                            request.versionId().toString(), relation.predicate(), subject.mentionId(),
                            object.mentionId(), evidenceChunkId,
                            relation.evidenceCharStart(), relation.evidenceCharEnd(),
                            evidenceDocumentStart, evidenceDocumentEnd, now));
                }
            }
            counters.put("relations_accepted", accepted.size());
            counters.put("relations_rejected", rejected);

            if (!mentions.isEmpty()) {
                graphClient.upsertMentions(mentions);
            }
            if (!accepted.isEmpty()) {
                graphClient.upsertRelations(accepted);
            }
            String graphStatus = rejected > 0 ? "PARTIAL" : "GRAPH_COMMITTED";
            cacheClient.markGraphCommitted(request, graphStatus, counters, clock.instant());
            log.info("Graph commit tenant={} version={} status={} mentions={} relations={} rejected={}",
                    request.tenantId(), request.versionId(), graphStatus, mentions.size(),
                    accepted.size(), rejected);
        } catch (Exception e) {
            try {
                cacheClient.markGraphFailed(request, "FAILED", counters, clock.instant());
            } catch (Exception markFailure) {
                log.warn("Could not mark graph failure tenant={} version={}: {}",
                        request.tenantId(), request.versionId(), markFailure.toString());
            }
            log.warn("Graph commit failed tenant={} version={}: {}",
                    request.tenantId(), request.versionId(), e.toString());
        }
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

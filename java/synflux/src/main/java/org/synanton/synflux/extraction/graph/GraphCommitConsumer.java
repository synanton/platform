package org.synanton.synflux.extraction.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ExtractionRequestRow;
import org.synanton.ingestioncache.domain.ExtractionResultRow;
import org.synanton.ingestioncache.domain.GraphCommitRequestRow;
import org.synanton.synflux.config.ExtractionConsumerProperties;
import org.synanton.synflux.config.ExtractionGraphProperties;

/**
 * PR #3.5: independent retry for graph commits. Extraction status stays
 * KNOWLEDGE_READY; this scanner re-runs GraphWriter for PENDING/FAILED versions
 * using the already-persisted extraction results (no LLM re-call).
 */
@Component
public class GraphCommitConsumer {

    private static final Logger log = LoggerFactory.getLogger(GraphCommitConsumer.class);
    private static final TypeReference<List<PreEntity>> ENTITIES = new TypeReference<>() {};
    private static final TypeReference<List<RelationCandidate>> RELATIONS = new TypeReference<>() {};

    private final IngestionCacheClient cacheClient;
    private final GraphWriter graphWriter;
    private final ExtractionConsumerProperties consumerProperties;
    private final ExtractionGraphProperties graphProperties;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public GraphCommitConsumer(IngestionCacheClient cacheClient, GraphWriter graphWriter,
                               ExtractionConsumerProperties consumerProperties,
                               ExtractionGraphProperties graphProperties, Clock clock) {
        this.cacheClient = cacheClient;
        this.graphWriter = graphWriter;
        this.consumerProperties = consumerProperties;
        this.graphProperties = graphProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${synflux.extraction-engine.graph.retry-poll-delay-ms:30000}")
    public void poll() {
        if (!graphProperties.enabled()) {
            return;
        }
        String tenant = consumerProperties.tenant();
        if (tenant == null || tenant.isBlank()) {
            return;
        }
        List<GraphCommitRequestRow> pending;
        try {
            pending = cacheClient.listPendingGraphCommits(tenant, 1, clock.instant());
        } catch (Exception e) {
            log.warn("Graph retry poll failed for tenant '{}': {}", tenant, e.toString());
            return;
        }
        for (GraphCommitRequestRow row : pending) {
            try {
                Map<Integer, ExtractionResult> results = loadResults(row);
                List<ChunkRow> chunks = cacheClient.readChunks(row.tenantId(), row.contentRefId());
                graphWriter.commit(toRequest(row), chunks, results, row.graphAttemptCount());
            } catch (Exception e) {
                log.warn("Graph retry failed ref={} version={}: {}",
                        row.contentRefId(), row.versionId(), e.toString());
            }
        }
    }

    private Map<Integer, ExtractionResult> loadResults(GraphCommitRequestRow row) throws Exception {
        Map<Integer, ExtractionResult> results = new LinkedHashMap<>();
        for (ExtractionResultRow stored : cacheClient.listExtractionResults(row.tenantId(), row.contentRefId())) {
            List<PreEntity> entities = mapper.readValue(stored.entitiesJson(), ENTITIES);
            List<RelationCandidate> relations = mapper.readValue(stored.relationsJson(), RELATIONS);
            results.put(stored.chunkOrdinal(), new ExtractionResult(entities, relations));
        }
        return results;
    }

    private static ExtractionRequestRow toRequest(GraphCommitRequestRow row) {
        return new ExtractionRequestRow(row.tenantId(), row.connectorId(), row.sourceId(), row.resourceId(),
                row.eventId(), row.versionId(), row.contentRefId(), row.payloadJson(), row.requestedAt(),
                row.searchProjectedAt(), row.extractionStatus(), row.extractionAttemptCount(),
                row.extractionLastError(), row.extractionNextAttemptAt(), row.extractionDeadLettered());
    }
}

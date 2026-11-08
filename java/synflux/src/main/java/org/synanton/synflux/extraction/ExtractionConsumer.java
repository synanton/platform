package org.synanton.synflux.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ExtractionRequestRow;
import org.synanton.synflux.config.ExtractionConsumerProperties;

/**
 * RAKG Phase 2 background consumer (PR #2). Polls at most
 * {@code batch-documents} ready ExtractionRequested events, processes their
 * chunks sequentially through the configured adapter, stores the pre-Relix
 * output, and advances PENDING -> EXTRACTING -> KNOWLEDGE_READY/
 * EXTRACTION_FAILED. No SHACL/Relix graph commit here.
 *
 * <p>One scheduled invocation at a time (fixedDelay); the scheduler pool is
 * sized in application.yml so workflow recovery is not blocked.
 */
@Component
public class ExtractionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExtractionConsumer.class);

    private final IngestionCacheClient cacheClient;
    private final ExtractionEngineAdapter adapter;
    private final ExtractionConsumerProperties properties;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExtractionConsumer(
            IngestionCacheClient cacheClient,
            ExtractionEngineAdapter adapter,
            ExtractionConsumerProperties properties,
            Clock clock) {
        this.cacheClient = cacheClient;
        this.adapter = adapter;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${synflux.extraction-engine.consumer.poll-delay-ms:5000}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        String tenant = properties.tenant();
        if (tenant == null || tenant.isBlank()) {
            log.warn("Extraction consumer is enabled without a tenant; skipping poll");
            return;
        }
        List<ExtractionRequestRow> pending;
        try {
            pending = cacheClient.listPendingExtractionRequests(
                    tenant, properties.batchDocumentsOrDefault(), clock.instant());
        } catch (Exception e) {
            log.warn("Extraction poll failed for tenant '{}': {}", tenant, e.toString());
            return;
        }
        for (ExtractionRequestRow request : pending) {
            process(request);
        }
    }

    private void process(ExtractionRequestRow request) {
        try {
            cacheClient.markExtractionStarted(request);
            List<ChunkRow> chunks = cacheClient.readChunks(request.tenantId(), request.contentRefId());
            int processed = 0;
            for (ChunkRow chunk : chunks) {
                ExtractionResult result = adapter.extract(
                        new TextSegment(request.contentRefId() + ":" + chunk.chunkOrdinal(),
                                chunk.chunkText(), 0),
                        new ExtractionContext(request.tenantId(), request.contentRefId().toString(), Set.of()));
                cacheClient.upsertExtractionResult(
                        request.tenantId(), request.contentRefId(), chunk.chunkOrdinal(), request.versionId(),
                        mapper.writeValueAsString(result.preEntities()),
                        mapper.writeValueAsString(result.relations()), clock.instant());
                processed++;
            }
            cacheClient.markExtractionSucceeded(request, clock.instant());
            log.info("Extraction completed ref={} version={} chunks={}",
                    request.contentRefId(), request.versionId(), processed);
        } catch (Exception e) {
            int attempts = request.attemptCount() + 1;
            Instant nextAttemptAt = clock.instant().plusSeconds(backoffSeconds(attempts));
            cacheClient.markExtractionFailed(request, attempts, truncate(e.getMessage()), nextAttemptAt);
            if (attempts >= properties.maxAttemptsOrDefault()) {
                log.error("Extraction failed ref={} version={} attempt={} (retrying with capped backoff): {}",
                        request.contentRefId(), request.versionId(), attempts, e.toString());
            } else {
                log.warn("Extraction failed ref={} version={} attempt={} next={}: {}",
                        request.contentRefId(), request.versionId(), attempts, nextAttemptAt, e.toString());
            }
        }
    }

    private long backoffSeconds(int attempts) {
        long base = properties.retryBackoffSecondsOrDefault();
        long multiplier = 1L << Math.min(Math.max(0, attempts - 1), 5);
        return Math.min(300, base * multiplier);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}

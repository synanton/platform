package org.synanton.synflux.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.synanton.synflux.extraction.graph.GraphWriter;
import org.synanton.synflux.extraction.provider.ExtractionAdapterFactory;
import org.synanton.synflux.extraction.provider.ExtractionAdapterResolver;
import org.synanton.synflux.extraction.provider.ProviderConfigInvalidException;
import org.synanton.synflux.extraction.provider.ResolvedExtractionAdapter;

/**
 * RAKG Phase 2 background consumer (PR #2). Polls at most
 * {@code batch-documents} ready ExtractionRequested events, processes their
 * chunks sequentially through the configured adapter, stores the pre-Relix
 * output, and advances PENDING -> EXTRACTING -> KNOWLEDGE_READY/
 * EXTRACTION_FAILED. After extraction succeeds, GraphWriter validates relations
 * and persists the isolated mention graph (graph_status); canonical resolution is
 * PR #4.
 *
 * <p>One scheduled invocation at a time (fixedDelay); the scheduler pool is
 * sized in application.yml so workflow recovery is not blocked.
 */
@Component
public class ExtractionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExtractionConsumer.class);

    private final IngestionCacheClient cacheClient;
    private final ExtractionAdapterResolver adapters;
    private final ExtractionConsumerProperties properties;
    private final GraphWriter graphWriter;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExtractionConsumer(
            IngestionCacheClient cacheClient,
            ExtractionEngineAdapter adapter,
            ExtractionConsumerProperties properties,
            GraphWriter graphWriter,
            Clock clock) {
        this(cacheClient, ExtractionAdapterFactory.fixed(adapter), properties, graphWriter, clock);
    }

    @Autowired
    public ExtractionConsumer(
            IngestionCacheClient cacheClient,
            ExtractionAdapterResolver adapters,
            ExtractionConsumerProperties properties,
            GraphWriter graphWriter,
            Clock clock) {
        this.cacheClient = cacheClient;
        this.adapters = adapters;
        this.properties = properties;
        this.graphWriter = graphWriter;
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
        ResolvedExtractionAdapter resolved;
        try {
            String persisted = cacheClient.readExtractionProvider(request).orElse(null);
            if (persisted != null && !persisted.isBlank()) {
                resolved = adapters.forProvider(persisted);
            } else {
                resolved = adapters.forTenant(request.tenantId());
                // The provider decision must be durable before extraction runs so a
                // retry cannot silently switch providers if config changes mid-run.
                cacheClient.markExtractionProvider(request, resolved.provider().canonical());
            }
        } catch (ProviderConfigInvalidException invalidConfig) {
            cacheClient.markExtractionFailedTerminal(request, properties.maxAttemptsOrDefault(),
                    "PROVIDER_CONFIG_INVALID: " + invalidConfig.getMessage());
            log.error("Extraction provider configuration invalid tenant={} ref={} version={}: {}",
                    request.tenantId(), request.contentRefId(), request.versionId(),
                    invalidConfig.getMessage());
            return;
        } catch (Exception providerFailure) {
            int attempts = request.attemptCount() + 1;
            Instant nextAttemptAt = clock.instant().plusSeconds(backoffSeconds(attempts));
            cacheClient.markExtractionFailed(request, attempts,
                    "PROVIDER_RESOLUTION_FAILED: " + truncate(providerFailure.getMessage()),
                    nextAttemptAt);
            log.warn("Extraction provider resolution failed tenant={} ref={} version={}: {}",
                    request.tenantId(), request.contentRefId(), request.versionId(),
                    providerFailure.toString());
            return;
        }
        ExtractionEngineAdapter adapter = resolved.adapter();
        try {
            cacheClient.markExtractionStarted(request);
            List<ChunkRow> chunks = cacheClient.readChunks(request.tenantId(), request.contentRefId());
            java.util.Map<Integer, ExtractionResult> resultsByOrdinal = new java.util.LinkedHashMap<>();
            int processed = 0;
            for (ChunkRow chunk : chunks) {
                ExtractionResult result = adapter.extract(
                        new TextSegment(request.contentRefId() + ":" + chunk.chunkOrdinal(),
                                chunk.chunkText(), 0),
                        new ExtractionContext(request.tenantId(), request.contentRefId().toString(), Set.of()));
                resultsByOrdinal.put(chunk.chunkOrdinal(), result);
                cacheClient.upsertExtractionResult(
                        request.tenantId(), request.contentRefId(), chunk.chunkOrdinal(), request.versionId(),
                        mapper.writeValueAsString(result.preEntities()),
                        mapper.writeValueAsString(result.relations()), clock.instant());
                processed++;
            }
            cacheClient.markExtractionSucceeded(
                    request, resolved.provider().canonical(), clock.instant());
            // extraction_status is final here; graph_status tracks the separate
            // validation/persistence stage and must not change extraction state.
            try {
                graphWriter.commit(request, chunks, resultsByOrdinal);
            } catch (Exception graphFailure) {
                log.warn("Graph writer failed after extraction tenant={} ref={} version={}: {}",
                        request.tenantId(), request.contentRefId(), request.versionId(),
                        graphFailure.toString());
            }
            log.info("Extraction completed tenant={} ref={} version={} chunks={} engine={} "
                            + "provider={} provider_source={}",
                    request.tenantId(), request.contentRefId(), request.versionId(), processed,
                    adapter.getClass().getSimpleName(), resolved.provider().canonical(),
                    resolved.providerSource());
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

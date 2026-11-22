package org.synanton.synflux.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.ExtractionIntegrity;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.LlmUsage;
import org.synanton.extraction.engine.QuarantinedLine;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.llm.LlmCorruptedOutputException;
import org.synanton.extraction.engine.llm.LlmIncompleteOutputException;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.client.TenantPolling;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ExtractionRequestRow;
import org.synanton.synflux.config.ExtractionConsumerProperties;
import org.synanton.synflux.extraction.graph.GraphCommitResult;
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
    private final ExtractionMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExtractionConsumer(
            IngestionCacheClient cacheClient,
            ExtractionEngineAdapter adapter,
            ExtractionConsumerProperties properties,
            GraphWriter graphWriter,
            Clock clock) {
        this(cacheClient, ExtractionAdapterFactory.fixed(adapter), properties, graphWriter, clock,
                new ExtractionMetrics(new SimpleMeterRegistry()));
    }

    public ExtractionConsumer(
            IngestionCacheClient cacheClient,
            ExtractionAdapterResolver adapters,
            ExtractionConsumerProperties properties,
            GraphWriter graphWriter,
            Clock clock) {
        this(cacheClient, adapters, properties, graphWriter, clock,
                new ExtractionMetrics(new SimpleMeterRegistry()));
    }

    @Autowired
    public ExtractionConsumer(
            IngestionCacheClient cacheClient,
            ExtractionAdapterResolver adapters,
            ExtractionConsumerProperties properties,
            GraphWriter graphWriter,
            Clock clock,
            ExtractionMetrics metrics) {
        this.cacheClient = cacheClient;
        this.adapters = adapters;
        this.properties = properties;
        this.graphWriter = graphWriter;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${synflux.extraction-engine.consumer.poll-delay-ms:5000}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        List<String> tenants;
        try {
            tenants = TenantPolling.resolve(properties.tenant(), cacheClient);
        } catch (Exception e) {
            log.warn("Extraction tenant discovery failed: {}", e.toString());
            return;
        }
        TenantPolling.forEachConcurrent(tenants, this::pollTenant);
    }

    private void pollTenant(String tenant) {
        List<ExtractionRequestRow> pending;
        try {
            pending = cacheClient.listPendingExtractionRequests(
                    tenant, properties.batchDocumentsOrDefault(), clock.instant());
        } catch (Exception e) {
            log.warn("Extraction poll failed for tenant '{}': {}", tenant, e.toString());
            return;
        }
        for (ExtractionRequestRow request : pending) {
            try {
                process(request);
            } catch (Exception e) {
                log.warn("Extraction processing failed for tenant '{}': {}", tenant, e.toString());
            }
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
        long startedNanos = System.nanoTime();
        try {
            cacheClient.markExtractionStarted(request);
            List<ChunkRow> chunks = cacheClient.readChunks(request.tenantId(), request.contentRefId());
            java.util.Map<Integer, ExtractionResult> resultsByOrdinal = new java.util.LinkedHashMap<>();
            java.util.Set<String> normalizedEntities = new java.util.LinkedHashSet<>();
            int entityMentions = 0;
            int relationCandidates = 0;
            int processed = 0;
            long promptTokens = 0;
            long completionTokens = 0;
            long totalTokens = 0;
            boolean usageObserved = false;
            String finishReason = null;
            for (ChunkRow chunk : chunks) {
                ExtractionResult result = adapter.extract(
                        new TextSegment(request.contentRefId() + ":" + chunk.chunkOrdinal(),
                                chunk.chunkText(), 0),
                        new ExtractionContext(request.tenantId(), request.contentRefId().toString(), Set.of(),
                                request.versionId().toString()));
                if (!result.quarantined().isEmpty()) {
                    log.warn("Extraction quarantined {} line(s) tenant={} ref={} version={} chunk={} "
                                    + "integrity={} reasons={}",
                            result.quarantined().size(), request.tenantId(),
                            request.contentRefId(), request.versionId(), chunk.chunkOrdinal(),
                            result.integrity(), summarizeQuarantine(result.quarantined()));
                }
                if (result.integrity() == ExtractionIntegrity.CORRUPTED) {
                    throw new LlmCorruptedOutputException(result);
                }
                resultsByOrdinal.put(chunk.chunkOrdinal(), result);
                LlmUsage usage = result.llmUsage();
                if (usage != null && usage.hasTokenCounts()) {
                    usageObserved = true;
                    promptTokens += usage.promptTokens();
                    completionTokens += usage.completionTokens();
                    totalTokens += usage.totalTokens();
                    finishReason = usage.finishReason();
                }
                entityMentions += result.preEntities().size();
                relationCandidates += result.relations().size();
                for (var entity : result.preEntities()) {
                    normalizedEntities.add(entity.surfaceForm() + "|" + entity.proposedType());
                }
                cacheClient.upsertExtractionResult(
                        request.tenantId(), request.contentRefId(), chunk.chunkOrdinal(), request.versionId(),
                        mapper.writeValueAsString(result.preEntities()),
                        mapper.writeValueAsString(result.relations()), clock.instant());
                processed++;
            }
            long extractionEndNanos = System.nanoTime();
            cacheClient.markExtractionSucceeded(
                    request, resolved.provider().canonical(), clock.instant());
            // extraction_status is final here; graph_status tracks the separate
            // validation/persistence stage and must not change extraction state.
            GraphCommitResult graphResult;
            try {
                graphResult = graphWriter.commit(request, chunks, resultsByOrdinal);
            } catch (Exception graphFailure) {
                graphResult = new GraphCommitResult(0, 0, 0, List.of(), "FAILED");
                log.warn("Graph writer failed after extraction tenant={} ref={} version={}: {}",
                        request.tenantId(), request.contentRefId(), request.versionId(),
                        graphFailure.toString());
            }
            if (graphResult == null) {
                graphResult = new GraphCommitResult(0, 0, 0, List.of(), "NOT_CLAIMED");
            }
            long graphEndNanos = System.nanoTime();
            long durationMs = (graphEndNanos - startedNanos) / 1_000_000;
            long extractionMs = (extractionEndNanos - startedNanos) / 1_000_000;
            long graphMs = (graphEndNanos - extractionEndNanos) / 1_000_000;
            String processingOutcome = processingOutcome(graphResult.status());
            String provider = resolved.provider().canonical();
            metrics.recordExtraction(request.tenantId(), provider, processingOutcome, durationMs,
                    entityMentions, relationCandidates, graphResult.accepted(), graphResult.rejected());
            log.info("Extraction completed tenant={} ref={} version={} chunks={} engine={} "
                            + "provider={} provider_source={}",
                    request.tenantId(), request.contentRefId(), request.versionId(), processed,
                    adapter.getClass().getSimpleName(), provider, resolved.providerSource());
            LlmUsage aggregateUsage = usageObserved
                    ? new LlmUsage(finishReason == null ? "unknown" : finishReason,
                            (int) promptTokens, (int) completionTokens, (int) totalTokens)
                    : LlmUsage.NONE;
            logStructuredEvent(request, resolved, adapter, durationMs, extractionMs, graphMs,
                    entityMentions, normalizedEntities.size(), relationCandidates, graphResult,
                    processingOutcome, aggregateUsage);
        } catch (LlmIncompleteOutputException incomplete) {
            int attempts = request.attemptCount() + 1;
            cacheClient.markExtractionFailedTerminal(request, attempts, incomplete.getMessage());
            log.error("Extraction output incomplete tenant={} ref={} version={}: {}",
                    request.tenantId(), request.contentRefId(), request.versionId(),
                    incomplete.getMessage());
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

    private void logStructuredEvent(ExtractionRequestRow request,
                                    ResolvedExtractionAdapter resolved,
                                    ExtractionEngineAdapter adapter,
                                    long durationMs, long extractionMs, long graphMs,
                                    int entityMentions, int normalizedEntities,
                                    int relationCandidates, GraphCommitResult graphResult,
                                    String processingOutcome, LlmUsage llmUsage) {
        try {
            java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("event", "extraction_completed");
            event.put("tenant_id", request.tenantId());
            event.put("tenant_class", ExtractionMetrics.tenantClass(request.tenantId()));
            event.put("source_version_id", request.versionId().toString());
            event.put("document_id", request.resourceId());
            event.put("engine", adapter.getClass().getSimpleName());
            event.put("provider", resolved.provider().canonical());
            event.put("provider_source", resolved.providerSource());
            event.put("duration_ms", durationMs);
            long indexingMs = request.searchProjectedAt() != null && request.requestedAt() != null
                    ? Math.max(0, request.searchProjectedAt().toEpochMilli()
                    - request.requestedAt().toEpochMilli()) : 0;
            java.util.Map<String, Object> durations = new java.util.LinkedHashMap<>();
            durations.put("extraction", extractionMs);
            durations.put("graph", graphMs);
            durations.put("indexing", indexingMs);
            durations.put("promotion", 0L);
            durations.put("activation", 0L);
            event.put("durations_ms", durations);
            event.put("entity_mentions", entityMentions);
            event.put("normalized_entities", normalizedEntities);
            event.put("relation_candidates", relationCandidates);
            event.put("graph_relation_candidates", graphResult.candidates());
            event.put("relations_accepted", graphResult.accepted());
            event.put("relations_rejected", graphResult.rejected());
            event.put("rejection_reason_codes", graphResult.rejectionCodes());
            event.put("rejection_reason_counts", graphResult.rejectionCounts());
            event.put("rejection_predicate_counts", graphResult.rejectionPredicateCounts());
            event.put("rejection_subject_type_counts", graphResult.rejectionSubjectTypeCounts());
            event.put("rejection_object_type_counts", graphResult.rejectionObjectTypeCounts());
            event.put("processing_outcome", processingOutcome);
            if (llmUsage != null && llmUsage.hasTokenCounts()) {
                java.util.Map<String, Object> usage = new java.util.LinkedHashMap<>();
                usage.put("finish_reason", llmUsage.finishReason());
                usage.put("prompt_tokens", llmUsage.promptTokens());
                usage.put("completion_tokens", llmUsage.completionTokens());
                usage.put("total_tokens", llmUsage.totalTokens());
                event.put("llm_usage", usage);
            }
            log.info("extraction_completed {}", mapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Could not serialize extraction completion event", e);
        }
    }

    private static String processingOutcome(String graphStatus) {
        return switch (graphStatus) {
            case "MENTIONS_COMMITTED" -> "COMPLETED";
            case "PARTIAL" -> "PARTIAL";
            case "GRAPH_DISABLED" -> "GRAPH_DISABLED";
            case "NOT_CLAIMED" -> "NOT_CLAIMED";
            default -> "GRAPH_FAILED";
        };
    }

    private long backoffSeconds(int attempts) {
        long base = properties.retryBackoffSecondsOrDefault();
        long multiplier = 1L << Math.min(Math.max(0, attempts - 1), 5);
        return Math.min(300, base * multiplier);
    }

    private static String summarizeQuarantine(List<QuarantinedLine> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(5, lines.size()); i++) {
            QuarantinedLine line = lines.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append(line.lineNumber()).append(':').append(line.reasonCode());
        }
        if (lines.size() > 5) {
            out.append(",…");
        }
        return out.toString();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}

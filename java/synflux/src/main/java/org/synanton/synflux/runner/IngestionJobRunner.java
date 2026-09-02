package org.synanton.synflux.runner;

import org.synanton.common.kafka.IngestJobRequest;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.JobRow;
import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.stage.*;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ContentPullPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Component
public class IngestionJobRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobRunner.class);

    private final SynfluxProperties props;
    private final ContentPullPort pullPort;
    private final IngestionCacheClient cacheClient;
    private final AcquireStage acquireStage;
    private final ExtractionStage extractionStage;
    private final SemanticChunkStage semanticChunkStage;
    private final PipelineStage<ChunkedDocument, ChunkedDocument> annotationStage;
    private final PipelineStage<ChunkedDocument, ChunkedDocument> enrichStage;
    private final PipelineStage<ChunkedDocument, ChunkedDocument> embedStage;
    private final PersistStage persistStage;

    private final Map<UUID, JobRow> inMemoryJobs = new ConcurrentHashMap<>();

    public IngestionJobRunner(
        SynfluxProperties props,
        ContentPullPort pullPort,
        IngestionCacheClient cacheClient,
        AcquireStage acquireStage,
        ExtractionStage extractionStage,
        SemanticChunkStage semanticChunkStage,
        PipelineStage<ChunkedDocument, ChunkedDocument> annotationStage,
        PipelineStage<ChunkedDocument, ChunkedDocument> enrichStage,
        PipelineStage<ChunkedDocument, ChunkedDocument> embedStage,
        PersistStage persistStage
    ) {
        this.props = props;
        this.pullPort = pullPort;
        this.cacheClient = cacheClient;
        this.acquireStage = acquireStage;
        this.extractionStage = extractionStage;
        this.semanticChunkStage = semanticChunkStage;
        this.annotationStage = annotationStage;
        this.enrichStage = enrichStage;
        this.embedStage = embedStage;
        this.persistStage = persistStage;
    }

    public UUID startJob(String tenant, String source, String path) {
        UUID jobId = UUID.randomUUID();
        return startJobWithId(tenant, source, path, jobId, "RUNNING");
    }

    public UUID startKafkaJob(IngestJobRequest req) {
        // Job was already created with QUEUED state; update to PROCESSING then run.
        JobRow processing = new JobRow(req.tenantId(), req.jobId(), Instant.now(), null,
            "PROCESSING", req.source(), req.sourcePath(), 0, 0, null, 0, 0, 0, 0, 0, 0, 0);
        inMemoryJobs.put(req.jobId(), processing);
        persistJob(processing);

        ExecutorService pool = Executors.newFixedThreadPool(props.ingest().parallelism());
        runIngestion(req.tenantId(), req.source(), req.sourcePath(), req.jobId(), pool);
        return req.jobId();
    }

    private UUID startJobWithId(String tenant, String source, String path, UUID jobId, String initialState) {
        Instant now = Instant.now();
        JobRow job = new JobRow(tenant, jobId, now, null, initialState, source, path, 0, 0, null, 0, 0, 0, 0, 0, 0, 0);
        inMemoryJobs.put(jobId, job);
        persistJob(job);

        ExecutorService pool = Executors.newFixedThreadPool(props.ingest().parallelism());
        CompletableFuture.runAsync(() -> runIngestion(tenant, source, path, jobId, pool), pool);
        return jobId;
    }

    private void runIngestion(String tenant, String source, String path, UUID jobId, ExecutorService pool) {
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        String[] lastError = {null};

        StageContext ctx = new StageContext(tenant, jobId.toString(), props);
        String rootUri;
        if ("synvault".equalsIgnoreCase(source) || "content_ref".equalsIgnoreCase(source)) {
            rootUri = path.startsWith("synvault://") ? path : "synvault://" + tenant + "/" + path;
        } else {
            rootUri = path.startsWith("file://") ? path : "file://" + path;
        }

        try (Stream<ContentRef> refs = pullPort.discover(tenant, rootUri)) {
            List<ContentRef> refList = refs.toList();
            List<Future<?>> futures = new ArrayList<>();

            for (ContentRef ref : refList) {
                futures.add(pool.submit(() -> {
                    try {
                        // Acquire and check idempotency
                        var acquired = acquireStage.apply(ref, ctx);
                        var existing = cacheClient.readManifest(tenant, acquired.contentRefId());
                        if (existing.isPresent()) {
                            String existingState = existing.get().state();
                            // Skip if already at or past the terminal state for current pipeline config
                            boolean alreadyDone = "EMBEDDED".equals(existingState)
                                || (!props.pipeline().embeddingEnabled() && "ENRICHED".equals(existingState))
                                || (!props.pipeline().enrichmentEnabled() && !props.pipeline().embeddingEnabled() && "CHUNKED".equals(existingState));
                            if (alreadyDone) {
                                log.debug("Skipping already-processed doc (state={}): {}", existingState, ref.uri());
                                return null;
                            }
                        }

                        var parsed = extractionStage.apply(acquired, ctx);
                        var chunked = semanticChunkStage.apply(parsed, ctx);

                        // AAP-1: annotation runs on chunked content today. Once v1.23's masking
                        // stage (SEC-4) lands, this MUST move after it - see AnnotationStage's
                        // class-level note on Invariant 5.
                        ChunkedDocument annotated = props.pipeline().annotationEnabled()
                            ? annotationStage.apply(chunked, ctx) : chunked;

                        ChunkedDocument enriched = props.pipeline().enrichmentEnabled()
                            ? enrichStage.apply(annotated, ctx) : annotated;
                        ChunkedDocument embedded = props.pipeline().embeddingEnabled()
                            ? embedStage.apply(enriched, ctx) : enriched;

                        persistStage.apply(embedded, ctx);
                        processed.incrementAndGet();
                    } catch (Exception e) {
                        log.warn("Error processing {}: {}", ref.uri(), e.getMessage());
                        errors.incrementAndGet();
                    }
                    return null;
                }));
            }

            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) { log.warn("Future error: {}", e.getMessage()); }
            }
        } catch (Exception e) {
            log.error("Job {} failed: {}", jobId, e.getMessage());
            lastError[0] = e.getMessage();
        } finally {
            pool.shutdown();
        }

        Instant completed = Instant.now();
        String finalState = errors.get() > 0 && processed.get() == 0 ? "FAILED" : "SUCCEEDED";
        JobRow startedJob = inMemoryJobs.get(jobId);
        JobRow finalJob = new JobRow(tenant, jobId, startedJob.startedAt(), completed,
            finalState, startedJob.source(), startedJob.sourcePath(),
            processed.get(), errors.get(), lastError[0], 0, 0, 0, 0, 0, 0, 0);
        inMemoryJobs.put(jobId, finalJob);
        persistJob(finalJob);
        log.info("Job {} completed: processed={}, errors={}", jobId, processed.get(), errors.get());
    }

    private void persistJob(JobRow job) {
        try { cacheClient.upsertJob(job); } catch (Exception e) { log.warn("Cannot persist job: {}", e.getMessage()); }
    }

    public Optional<JobRow> getJob(String tenant, UUID jobId) {
        var inMem = inMemoryJobs.get(jobId);
        if (inMem != null) return Optional.of(inMem);
        return cacheClient.listJobs(tenant, 1000).stream()
            .filter(j -> j.jobId().equals(jobId)).findFirst();
    }

    public List<JobRow> listJobs(String tenant) {
        return cacheClient.listJobs(tenant, 100);
    }
}

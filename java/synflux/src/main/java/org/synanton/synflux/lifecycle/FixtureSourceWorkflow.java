package org.synanton.synflux.lifecycle;

import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.stage.AcquireStage;
import org.synanton.synflux.pipeline.stage.ExtractionStage;
import org.synanton.synflux.pipeline.stage.PersistStage;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ContentPullPort;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

@Service
public class FixtureSourceWorkflow {

    private final IngestionCacheClient cache;
    private final ContentPullPort contentPullPort;
    private final AcquireStage acquireStage;
    private final ExtractionStage extractionStage;
    private final PipelineStage<ParsedDocument, ChunkedDocument> chunkStage;
    private final PipelineStage<ChunkedDocument, ChunkedDocument> annotationStage;
    private final PipelineStage<ChunkedDocument, ChunkedDocument> enrichmentStage;
    private final PipelineStage<ChunkedDocument, ChunkedDocument> embeddingStage;
    private final PersistStage persistStage;
    private final SynfluxProperties properties;
    private final FixtureIngestionService lifecycle;
    private final OperationService operations;
    private final Executor executor;
    private final FilesystemFixturePolicy filesystemPolicy;
    private final Set<UUID> activeOperations = ConcurrentHashMap.newKeySet();

    public FixtureSourceWorkflow(
            IngestionCacheClient cache,
            ContentPullPort contentPullPort,
            AcquireStage acquireStage,
            ExtractionStage extractionStage,
            PipelineStage<ParsedDocument, ChunkedDocument> chunkStage,
            PipelineStage<ChunkedDocument, ChunkedDocument> annotationStage,
            PipelineStage<ChunkedDocument, ChunkedDocument> enrichmentStage,
            PipelineStage<ChunkedDocument, ChunkedDocument> embeddingStage,
            PersistStage persistStage,
            SynfluxProperties properties,
            FixtureIngestionService lifecycle,
            OperationService operations,
            Executor executor,
            FilesystemFixturePolicy filesystemPolicy) {
        this.cache = cache;
        this.contentPullPort = contentPullPort;
        this.acquireStage = acquireStage;
        this.extractionStage = extractionStage;
        this.chunkStage = chunkStage;
        this.annotationStage = annotationStage;
        this.enrichmentStage = enrichmentStage;
        this.embeddingStage = embeddingStage;
        this.persistStage = persistStage;
        this.properties = properties;
        this.lifecycle = lifecycle;
        this.operations = operations;
        this.executor = executor;
        this.filesystemPolicy = filesystemPolicy;
    }

    public Submission submit(
            VerifiedIngestionContext context,
            String sourceId,
            String resourceId,
            String requestId,
            String idempotencyKey) {
        SourceRow source = cache.readSource(context.tenantId(), sourceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "source not found"));
        if (!"ACTIVE".equals(source.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "source is deleted");
        }
        if (!"file".equals(source.connectorType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fixture supports filesystem sources only");
        }
        OperationRow operation = operations.accept(context, requestId, idempotencyKey);
        UUID runId = runId(operation.operationId());
        var previousRun = cache.readIngestionRun(context.tenantId(), runId);
        if (previousRun.isPresent() && previousRun.get().completedAt() != null) {
            return new Submission(previousRun.get(), operations.completeFromRun(operation, previousRun.get()));
        }
        if (!activeOperations.add(operation.operationId())) {
            IngestionRunRow runningRun = previousRun.orElseGet(() -> scheduledRun(context, sourceId, runId, operation));
            return new Submission(runningRun, operation);
        }

        OperationRow running = operations.markRunning(operation, 1);
        if ("CANCELLED".equals(running.status()) || operations.cancellationRequested(operation.operationId())) {
            activeOperations.remove(operation.operationId());
            IngestionRunRow cancelled = lifecycle.recordEmptyRun(
                context, runId, operation.operationId(), sourceId,
                FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            return new Submission(cancelled, operations.completeFromRun(running, cancelled));
        }
        IngestionRunRow scheduled = scheduledRun(context, sourceId, runId, running);
        try {
            executor.execute(() -> {
                try {
                    process(context, source, sourceId, resourceId, runId, running);
                } finally {
                    activeOperations.remove(operation.operationId());
                }
            });
        } catch (RuntimeException rejected) {
            activeOperations.remove(operation.operationId());
            IngestionRunRow failed = lifecycle.recordEmptyRun(
                context, runId, operation.operationId(), sourceId,
                FixtureIngestionService.EmptyRunDisposition.FAILED_BEFORE_DISCOVERY, rejected);
            return new Submission(failed, operations.completeFromRun(running, failed));
        }
        return new Submission(scheduled, running);
    }

    public Submission ingest(
            VerifiedIngestionContext context,
            String sourceId,
            String resourceId,
            String requestId,
            String idempotencyKey) {
        SourceRow source = cache.readSource(context.tenantId(), sourceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "source not found"));
        if (!"ACTIVE".equals(source.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "source is deleted");
        }
        if (!"file".equals(source.connectorType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fixture supports filesystem sources only");
        }

        OperationRow operation = operations.accept(context, requestId, idempotencyKey);
        UUID runId = runId(operation.operationId());
        var priorRun = cache.readIngestionRun(context.tenantId(), runId);
        if (priorRun.isPresent() && priorRun.get().completedAt() != null) {
            OperationRow current = operations.completeFromRun(operation, priorRun.get());
            return new Submission(priorRun.get(), current);
        }

        OperationRow running = operations.markRunning(operation, 1);
        return process(context, source, sourceId, resourceId, runId, running);
    }

    private Submission process(
            VerifiedIngestionContext context,
            SourceRow source,
            String sourceId,
            String resourceId,
            UUID runId,
            OperationRow running) {
        UUID operationId = running.operationId();
        if ("CANCELLED".equals(running.status()) || operations.cancellationRequested(operationId)) {
            IngestionRunRow cancelled = lifecycle.recordEmptyRun(
                context, runId, operationId, sourceId,
                FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            return new Submission(cancelled, operations.completeFromRun(running, cancelled));
        }
        ContentRef ref;
        try (Stream<ContentRef> discovered = contentPullPort.discover(context.tenantId(), source.sourceEndpoint())) {
            ref = discovered
                .filter(candidate -> filesystemPolicy.resourceId(source.sourceEndpoint(), candidate).equals(resourceId))
                .min(Comparator.comparing(ContentRef::uri))
                .orElse(null);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception failure) {
            IngestionRunRow failed = lifecycle.recordEmptyRun(
                context, runId, operationId, sourceId,
                FixtureIngestionService.EmptyRunDisposition.FAILED_BEFORE_DISCOVERY, failure);
            return new Submission(failed, operations.completeFromRun(running, failed));
        }
        if (ref == null) {
            IngestionRunRow failed = lifecycle.recordEmptyRun(
                context, runId, operationId, sourceId,
                FixtureIngestionService.EmptyRunDisposition.FAILED_BEFORE_DISCOVERY);
            return new Submission(failed, operations.completeFromRun(running, failed));
        }
        if (operations.cancellationRequested(operationId)) {
            IngestionRunRow cancelled = lifecycle.recordEmptyRun(
                context, runId, operationId, sourceId,
                FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            return new Submission(cancelled, operations.completeFromRun(running, cancelled));
        }
        try {
            StageContext stageContext = new StageContext(context.tenantId(), runId.toString(), properties);
            AcquiredDocument acquired = acquireStage.apply(ref, stageContext);
            IngestionRunRow run = lifecycle.ingest(
                context, runId, operationId, source.connectorId(), sourceId, resourceId,
                ref, acquired.bytes(), acquired.contentCacheReference(), ignored -> {
                    if (operations.cancellationRequested(operationId)) {
                        throw new CancellationException("Operation cancellation requested");
                    }
                    return publishThroughPipeline(acquired, stageContext, operationId);
                }
            );
            return new Submission(run, operations.completeFromRun(running, run));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception failure) {
            IngestionRunRow failed = lifecycle.recordAcquisitionFailure(
                context, runId, operationId, source.connectorId(), sourceId, resourceId, failure);
            return new Submission(failed, operations.completeFromRun(running, failed));
        }
    }

    public Submission deleteResource(
            VerifiedIngestionContext context,
            String sourceId,
            String resourceId,
            String requestId,
            String idempotencyKey) {
        SourceRow source = cache.readSource(context.tenantId(), sourceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "source not found"));
        if (!"ACTIVE".equals(source.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "source is deleted");
        }
        OperationRow operation = operations.accept(
            context, "DELETE /v1/sources/" + sourceId + "/resources", requestId, idempotencyKey);
        UUID runId = runId(operation.operationId());
        var priorRun = cache.readIngestionRun(context.tenantId(), runId);
        if (priorRun.isPresent() && priorRun.get().completedAt() != null) {
            return new Submission(priorRun.get(), operations.completeFromRun(operation, priorRun.get()));
        }
        OperationRow running = operations.markRunning(operation, 1);
        if ("CANCELLED".equals(running.status())) {
            IngestionRunRow cancelled = lifecycle.recordEmptyRun(
                context, runId, operation.operationId(), sourceId,
                FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            return new Submission(cancelled, operations.completeFromRun(running, cancelled));
        }
        if (operations.cancellationRequested(operation.operationId())) {
            IngestionRunRow cancelled = lifecycle.recordEmptyRun(
                context, runId, operation.operationId(), sourceId,
                FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            return new Submission(cancelled, operations.completeFromRun(running, cancelled));
        }
        IngestionRunRow run = lifecycle.recordDeletion(
            context, runId, operation.operationId(), source.connectorId(), sourceId, resourceId,
            FixtureIngestionService.DeletionSignal.DELETED
        );
        return new Submission(run, operations.completeFromRun(running, run));
    }

    public OperationRow cancelOperation(VerifiedIngestionContext context, UUID operationId) {
        OperationRow current = operations.read(context.tenantId(), operationId);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "operation not found");
        }
        OperationRow cancelled = operations.cancel(context.tenantId(), operationId);
        if ("CANCELLED".equals(cancelled.status())) {
            UUID runId = runId(operationId);
            var run = cache.readIngestionRun(context.tenantId(), runId);
            if (run.isPresent() && run.get().completedAt() == null) {
                lifecycle.recordEmptyRun(context, runId, operationId, run.get().sourceId(),
                    FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            }
        }
        return cancelled;
    }

    private UUID publishThroughPipeline(AcquiredDocument acquired, StageContext context, UUID operationId)
            throws Exception {
        if (operations.cancellationRequested(operationId)) {
            throw new CancellationException("Operation cancellation requested");
        }
        ParsedDocument parsed = extractionStage.apply(acquired, context);
        checkCancellation(operationId);
        ChunkedDocument chunked = chunkStage.apply(parsed, context);
        checkCancellation(operationId);
        ChunkedDocument annotated = context.props().pipeline().annotationEnabled()
            ? annotationStage.apply(chunked, context) : chunked;
        checkCancellation(operationId);
        ChunkedDocument enriched = context.props().pipeline().enrichmentEnabled()
            ? enrichmentStage.apply(annotated, context) : annotated;
        checkCancellation(operationId);
        ChunkedDocument embedded = context.props().pipeline().embeddingEnabled()
            ? embeddingStage.apply(enriched, context) : enriched;
        checkCancellation(operationId);
        persistStage.apply(embedded, context);
        return acquired.contentRefId();
    }

    private void checkCancellation(UUID operationId) {
        if (operations.cancellationRequested(operationId)) {
            throw new CancellationException("Operation cancellation requested");
        }
    }

    private static UUID runId(UUID operationId) {
        return UUID.nameUUIDFromBytes(
            ("synflux-ingestion-run:" + operationId).getBytes(StandardCharsets.UTF_8));
    }

    private IngestionRunRow scheduledRun(
            VerifiedIngestionContext context, String sourceId, UUID runId, OperationRow operation) {
        return lifecycle.scheduleRun(context, runId, operation.operationId(), sourceId);
    }

    public record Submission(IngestionRunRow run, OperationRow operation) {}
}

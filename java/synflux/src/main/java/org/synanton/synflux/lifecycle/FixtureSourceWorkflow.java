package org.synanton.synflux.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.contentcache.ContentIntegrityException;
import org.synanton.contentcache.ContentStoreException;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.WorkflowStateRow;
import org.synanton.ingestioncache.domain.WorkflowStepRow;
import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.synanton.synflux.pipeline.stage.AcquireStage;
import org.synanton.synflux.pipeline.stage.ExtractionStage;
import org.synanton.synflux.pipeline.stage.PersistStage;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ContentPullPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Durable orchestration for one fixture source resource. */
@Service
public class FixtureSourceWorkflow {

    private static final Logger log = LoggerFactory.getLogger(FixtureSourceWorkflow.class);
    private static final ObjectMapper WORKFLOW_MAPPER = new ObjectMapper();
    private static final String WORKFLOW_TYPE = "IngestionWorkflow";
    private static final int WORKFLOW_VERSION = 1;
    private static final int MAX_RETRIES = 3;
    private static final long WORKFLOW_LEASE_SECONDS = 30;

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
    private final Clock clock;
    private final Set<UUID> activeOperations = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService leaseRenewer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "synflux-workflow-lease-renewer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile WorkflowStepObserver workflowStepObserver = stepId -> {};

    public FixtureSourceWorkflow(
            IngestionCacheClient cache,
            ContentPullPort contentPullPort,
            AcquireStage acquireStage,
            ExtractionStage extractionStage,
            @Qualifier("chunkStage") PipelineStage<ParsedDocument, ChunkedDocument> chunkStage,
            @Qualifier("annotationStage") PipelineStage<ChunkedDocument, ChunkedDocument> annotationStage,
            @Qualifier("enrichStage") PipelineStage<ChunkedDocument, ChunkedDocument> enrichmentStage,
            @Qualifier("embedStage") PipelineStage<ChunkedDocument, ChunkedDocument> embeddingStage,
            PersistStage persistStage,
            SynfluxProperties properties,
            FixtureIngestionService lifecycle,
            OperationService operations,
            Executor executor,
            FilesystemFixturePolicy filesystemPolicy,
            Clock clock) {
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
        this.clock = clock;
    }

    void setWorkflowStepObserver(WorkflowStepObserver observer) {
        workflowStepObserver = observer == null ? stepId -> {} : observer;
    }

    @PreDestroy
    public void stopLeaseRenewer() {
        leaseRenewer.shutdownNow();
    }

    public Submission submit(
            VerifiedIngestionContext context,
            String sourceId,
            String resourceId,
            String requestId,
            String idempotencyKey) {
        SourceRow source = requireActiveFilesystemSource(context, sourceId);
        OperationRow operation = operations.accept(context, requestId, idempotencyKey);
        UUID runId = runId(operation.operationId());
        WorkflowStateRow workflow = ensureWorkflow(context, sourceId, resourceId, operation, runId);
        IngestionRunRow previousRun = cache.readIngestionRun(context.tenantId(), runId).orElse(null);
        if (previousRun != null && previousRun.completedAt() != null) {
            finishWorkflowFromRun(workflow, previousRun);
            return new Submission(previousRun, operations.completeFromRun(operation, previousRun));
        }
        if (!activeOperations.add(operation.operationId())) {
            IngestionRunRow currentRun = previousRun == null
                ? lifecycle.scheduleRun(context, runId, operation.operationId(), sourceId)
                : previousRun;
            return new Submission(currentRun, operation);
        }

        OperationRow running = operations.markRunning(operation, 1);
        if ("CANCELLED".equals(running.status())
                || operations.cancellationRequested(context.tenantId(), operation.operationId())) {
            activeOperations.remove(operation.operationId());
            IngestionRunRow cancelled = lifecycle.recordEmptyRun(
                context, runId, operation.operationId(), sourceId,
                FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            finishWorkflowFromRun(workflow, cancelled);
            return new Submission(cancelled, operations.completeFromRun(running, cancelled));
        }

        IngestionRunRow scheduled = lifecycle.scheduleRun(context, runId, operation.operationId(), sourceId);
        try {
            executor.execute(() -> {
                try {
                    process(context, source, sourceId, resourceId, runId, running, workflow);
                } finally {
                    activeOperations.remove(operation.operationId());
                }
            });
        } catch (RuntimeException rejected) {
            activeOperations.remove(operation.operationId());
            IngestionRunRow failed = lifecycle.recordEmptyRun(
                context, runId, operation.operationId(), sourceId,
                FixtureIngestionService.EmptyRunDisposition.FAILED_BEFORE_DISCOVERY, rejected);
            finishWorkflow(workflow, "FAILED", "SCHEDULE", "SCHEDULER_REJECTED", safeMessage(rejected));
            return new Submission(failed, operations.completeFromRun(running, failed));
        }
        return new Submission(scheduled, running);
    }

    /** Synchronous fixture/test entry; the public API uses {@link #submit}. */
    public Submission ingest(
            VerifiedIngestionContext context,
            String sourceId,
            String resourceId,
            String requestId,
            String idempotencyKey) {
        SourceRow source = requireActiveFilesystemSource(context, sourceId);
        OperationRow operation = operations.accept(context, requestId, idempotencyKey);
        UUID runId = runId(operation.operationId());
        WorkflowStateRow workflow = ensureWorkflow(context, sourceId, resourceId, operation, runId);
        IngestionRunRow previousRun = cache.readIngestionRun(context.tenantId(), runId).orElse(null);
        if (previousRun != null && previousRun.completedAt() != null) {
            finishWorkflowFromRun(workflow, previousRun);
            return new Submission(previousRun, operations.completeFromRun(operation, previousRun));
        }
        OperationRow running = operations.markRunning(operation, 1);
        lifecycle.scheduleRun(context, runId, operation.operationId(), sourceId);
        return process(context, source, sourceId, resourceId, runId, running, workflow);
    }

    /** Requeues all non-terminal source workflows after process start and on retry ticks. */
    public void recoverPending() {
        Instant now = clock.instant();
        for (String tenantId : cache.listTenantIds()) {
            for (WorkflowStateRow workflow : cache.listWorkflowStates(tenantId)) {
                if (workflow.terminal()
                        || (workflow.nextAttemptAt() != null && workflow.nextAttemptAt().isAfter(now))
                        || !activeOperations.add(workflow.operationId())) {
                    continue;
                }
                try {
                    executor.execute(() -> {
                        try {
                            resumeWorkflow(workflow);
                        } catch (Exception failure) {
                            log.error("Workflow recovery failed for {}", workflow.workflowId(), failure);
                        } finally {
                            activeOperations.remove(workflow.operationId());
                        }
                    });
                } catch (RuntimeException rejected) {
                    activeOperations.remove(workflow.operationId());
                    log.warn("Could not schedule workflow recovery {}: {}", workflow.workflowId(), rejected.toString());
                }
            }
        }
    }

    private Submission process(
            VerifiedIngestionContext context,
            SourceRow source,
            String sourceId,
            String resourceId,
            UUID runId,
            OperationRow operation,
            WorkflowStateRow workflow) {
        UUID leaseOwner = UUID.randomUUID();
        Instant leaseStarted = clock.instant();
        if (!cache.claimWorkflowLease(context.tenantId(), workflow.workflowId(), leaseOwner,
                leaseStarted, leaseStarted.plusSeconds(WORKFLOW_LEASE_SECONDS))) {
            IngestionRunRow current = cache.readIngestionRun(context.tenantId(), runId)
                .orElseGet(() -> lifecycle.scheduleRun(context, runId, operation.operationId(), sourceId));
            return new Submission(current, cache.readOperation(context.tenantId(), operation.operationId())
                .orElse(operation));
        }
        // Lease-loss propagation: a live worker whose renewal fails must stop before
        // its next side effect, not run to completion lease-less. The guard is checked
        // at every side-effecting boundary (discovery, acquire, each pipeline stage,
        // the version commit); on loss the exception propagates without a terminal
        // record, leaving the workflow non-terminal for the new leaseholder to resume.
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = leaseRenewer.scheduleAtFixedRate(() -> {
            Instant now = clock.instant();
            if (!cache.renewWorkflowLease(context.tenantId(), workflow.workflowId(), leaseOwner,
                    now.plusSeconds(WORKFLOW_LEASE_SECONDS))) {
                leaseLost.set(true);
                log.warn("Lost workflow lease for {} — halting before the next side effect",
                    workflow.workflowId());
            }
        }, WORKFLOW_LEASE_SECONDS / 3, WORKFLOW_LEASE_SECONDS / 3, TimeUnit.SECONDS);
        try {
            return processClaimed(context, source, sourceId, resourceId, runId, operation, workflow, leaseLost);
        } finally {
            heartbeat.cancel(false);
            cache.releaseWorkflowLease(context.tenantId(), workflow.workflowId(), leaseOwner);
        }
    }

    private void requireLease(AtomicBoolean leaseLost) {
        if (leaseLost.get()) {
            throw new WorkflowLeaseLostException();
        }
    }

    private Submission processClaimed(
            VerifiedIngestionContext context,
            SourceRow source,
            String sourceId,
            String resourceId,
            UUID runId,
            OperationRow operation,
            WorkflowStateRow workflow,
            AtomicBoolean leaseLost) {
        UUID operationId = operation.operationId();
        IngestionRunRow persistedRun = cache.readIngestionRun(context.tenantId(), runId).orElse(null);
        if (persistedRun != null && persistedRun.completedAt() != null) {
            finishWorkflowFromRun(workflow, persistedRun);
            return new Submission(persistedRun, operations.completeFromRun(operation, persistedRun));
        }
        WorkflowStateRow activeWorkflow = transitionWorkflow(
            workflow, "RUNNING", workflow.currentStep(), null, null, workflow.retryCount(), null);
        if ("CANCELLED".equals(operation.status())
                || operations.cancellationRequested(context.tenantId(), operationId)) {
            IngestionRunRow cancelled = lifecycle.recordEmptyRun(
                context, runId, operationId, sourceId, FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            finishWorkflowFromRun(activeWorkflow, cancelled);
            return new Submission(cancelled, operations.completeFromRun(operation, cancelled));
        }

        ContentRef ref;
        try {
            requireLease(leaseLost);
            ref = loadOrDiscover(context, source, sourceId, resourceId, activeWorkflow, leaseLost);
        } catch (WorkflowLeaseLostException e) {
            throw e;
        } catch (Exception failure) {
            return failOrRetryBeforeDiscovery(context, sourceId, runId, activeWorkflow, operation, failure);
        }
        if (ref == null) {
            return failBeforeDiscovery(context, sourceId, runId, activeWorkflow, operation, null);
        }
        FaultInjection.crashAt("after-discover");
        if (operations.cancellationRequested(context.tenantId(), operationId)) {
            IngestionRunRow cancelled = lifecycle.recordEmptyRun(
                context, runId, operationId, sourceId, FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            finishWorkflowFromRun(activeWorkflow, cancelled);
            return new Submission(cancelled, operations.completeFromRun(operation, cancelled));
        }

        StageContext stageContext = new StageContext(context.tenantId(), runId.toString(), properties);
        AcquiredDocument acquired;
        try {
            requireLease(leaseLost);
            acquired = loadOrAcquire(ref, stageContext, activeWorkflow, leaseLost);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (WorkflowLeaseLostException e) {
            throw e;
        } catch (Exception failure) {
            return failOrRetryAfterDiscovery(
                context, source, sourceId, resourceId, runId, activeWorkflow, operation, failure);
        }
        if (operations.cancellationRequested(context.tenantId(), operationId)) {
            IngestionRunRow cancelled = lifecycle.recordEmptyRun(
                context, runId, operationId, sourceId, FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            finishWorkflowFromRun(activeWorkflow, cancelled);
            return new Submission(cancelled, operations.completeFromRun(operation, cancelled));
        }

        FaultInjection.crashAt("after-acquire");
        WorkflowStepRow ingestStep = beginStep(activeWorkflow, "INGEST", Map.of(
            "content_ref_id", acquired.contentRefId().toString(),
            "content_cache_reference", acquired.contentCacheReference()));
        requireLease(leaseLost);
        boolean deferRetryable = activeWorkflow.retryCount() < MAX_RETRIES;
        IngestionRunRow run = lifecycle.ingest(
            context, runId, operationId, source.connectorId(), sourceId, resourceId,
            ref, acquired.bytes(), acquired.contentCacheReference(), activeWorkflow.workflowId().toString(),
            activeWorkflow.correlationId(),
            ingestStep.commandId().toString(), deferRetryable, (bytes, sourceVersionId) -> {
                requireLease(leaseLost);
                if (operations.cancellationRequested(context.tenantId(), operationId)) {
                    throw new CancellationException("Operation cancellation requested");
                }
                var published = publishThroughPipeline(
                    acquired, stageContext.withSourceVersionId(sourceVersionId), operationId, leaseLost);
                FaultInjection.crashAt("after-manifest");
                return published;
            }
        );
        if (run.completedAt() == null) {
            return retryWorkflow(activeWorkflow, ingestStep, operation, run.errorRef());
        }
        completeStep(ingestStep, Map.of("run_status", run.status()));
        workflowStepObserver.afterStepCompleted("INGEST");
        finishWorkflowFromRun(activeWorkflow, run);
        return new Submission(run, operations.completeFromRun(operation, run));
    }

    private ContentRef loadOrDiscover(
            VerifiedIngestionContext context,
            SourceRow source,
            String sourceId,
            String resourceId,
            WorkflowStateRow workflow,
            AtomicBoolean leaseLost) throws Exception {
        WorkflowStepRow existing = cache.readWorkflowStep(context.tenantId(), workflow.workflowId(), "DISCOVER")
            .orElse(null);
        if (existing != null && existing.completed()) {
            RefCheckpoint checkpoint = readJson(existing.outputJson(), RefCheckpoint.class);
            return checkpoint.found() ? checkpoint.toContentRef() : null;
        }
        WorkflowStepRow step = beginStep(workflow, "DISCOVER", Map.of(
            "source_id", sourceId, "resource_id", resourceId));
        ContentRef ref;
        try (Stream<ContentRef> discovered = contentPullPort.discover(context.tenantId(), source.sourceEndpoint())) {
            ref = discovered
                .filter(candidate -> filesystemPolicy.resourceId(source.sourceEndpoint(), candidate).equals(resourceId))
                .min(Comparator.comparing(ContentRef::uri))
                .orElse(null);
        }
        requireLease(leaseLost);
        completeStep(step, ref == null ? RefCheckpoint.notFound() : RefCheckpoint.from(ref));
        workflowStepObserver.afterStepCompleted("DISCOVER");
        return ref;
    }

    private AcquiredDocument loadOrAcquire(
            ContentRef ref, StageContext context, WorkflowStateRow workflow, AtomicBoolean leaseLost)
            throws Exception {
        WorkflowStepRow existing = cache.readWorkflowStep(context.tenant(), workflow.workflowId(), "ACQUIRE")
            .orElse(null);
        if (existing != null && existing.completed()) {
            AcquisitionCheckpoint checkpoint = readJson(existing.outputJson(), AcquisitionCheckpoint.class);
            return acquireStage.restore(
                ref, checkpoint.contentRefId(), checkpoint.sha256(), checkpoint.contentCacheReference(), context);
        }
        WorkflowStepRow step = beginStep(workflow, "ACQUIRE", Map.of(
            "source_uri", ref.uri(), "source_size", ref.sizeBytes()));
        requireLease(leaseLost);
        AcquiredDocument acquired = acquireStage.apply(ref, context);
        requireLease(leaseLost);
        completeStep(step, new AcquisitionCheckpoint(
            acquired.contentRefId(), acquired.sha256(), acquired.contentCacheReference()));
        workflowStepObserver.afterStepCompleted("ACQUIRE");
        return acquired;
    }

    private Submission failBeforeDiscovery(
            VerifiedIngestionContext context,
            String sourceId,
            UUID runId,
            WorkflowStateRow workflow,
            OperationRow operation,
            Exception failure) {
        Exception reason = failure == null
            ? new IllegalStateException("Requested resource was not discovered") : failure;
        IngestionRunRow failed = lifecycle.recordEmptyRun(
            context, runId, operation.operationId(), sourceId,
            FixtureIngestionService.EmptyRunDisposition.FAILED_BEFORE_DISCOVERY, reason);
        WorkflowStepRow step = getOrStartStep(workflow, "DISCOVER", Map.of("source_id", sourceId));
        failStep(step, reason);
        finishWorkflow(workflow, "FAILED", "DISCOVER", "SOURCE_DISCOVERY_FAILED", safeMessage(reason));
        return new Submission(failed, operations.completeFromRun(operation, failed));
    }

    private Submission failOrRetryBeforeDiscovery(
            VerifiedIngestionContext context,
            String sourceId,
            UUID runId,
            WorkflowStateRow workflow,
            OperationRow operation,
            Exception failure) {
        WorkflowStepRow step = getOrStartStep(workflow, "DISCOVER", Map.of("source_id", sourceId));
        if (retryableFailure(failure) && workflow.retryCount() < MAX_RETRIES) {
            IngestionRunRow retrying = lifecycle.recordRetryableFailure(
                context, runId, operation.operationId(), sourceId, failure);
            return markRetryable(workflow, step, operation, retrying, "DISCOVER", failure);
        }
        return failBeforeDiscovery(context, sourceId, runId, workflow, operation, failure);
    }

    private Submission failOrRetryAfterDiscovery(
            VerifiedIngestionContext context,
            SourceRow source,
            String sourceId,
            String resourceId,
            UUID runId,
            WorkflowStateRow workflow,
            OperationRow operation,
            Exception failure) {
        WorkflowStepRow step = getOrStartStep(workflow, "ACQUIRE", Map.of("resource_id", resourceId));
        if (retryableFailure(failure) && workflow.retryCount() < MAX_RETRIES) {
            IngestionRunRow retrying = lifecycle.recordRetryableFailure(
                context, runId, operation.operationId(), sourceId, failure);
            return markRetryable(workflow, step, operation, retrying, "ACQUIRE", failure);
        }
        IngestionRunRow failed = lifecycle.recordAcquisitionFailure(
            context, runId, operation.operationId(), source.connectorId(), sourceId, resourceId, failure);
        failStep(step, failure);
        finishWorkflowFromRun(workflow, failed);
        return new Submission(failed, operations.completeFromRun(operation, failed));
    }

    private Submission retryWorkflow(
            WorkflowStateRow workflow, WorkflowStepRow step, OperationRow operation, String errorRef) {
        Exception failure = new IllegalStateException(
            errorRef == null ? "Retryable ingestion failure" : errorDetail(workflow.tenantId(), errorRef));
        return markRetryable(workflow, step, operation,
            cache.readIngestionRun(workflow.tenantId(), workflow.runId()).orElseThrow(), "INGEST", failure);
    }

    private Submission markRetryable(
            WorkflowStateRow workflow,
            WorkflowStepRow step,
            OperationRow operation,
            IngestionRunRow run,
            String stepId,
            Exception failure) {
        int retryCount = workflow.retryCount() + 1;
        long delaySeconds = 1L << Math.min(retryCount - 1, 5);
        Instant nextAttemptAt = clock.instant().plusSeconds(delaySeconds);
        retryStep(step, failure, nextAttemptAt);
        transitionWorkflow(workflow, "RETRYABLE", stepId, "DEPENDENCY_UNAVAILABLE",
            safeMessage(failure), retryCount, nextAttemptAt);
        return new Submission(run, cache.readOperation(workflow.tenantId(), operation.operationId()).orElse(operation));
    }

    private WorkflowStepRow getOrStartStep(WorkflowStateRow workflow, String stepId, Object input) {
        return cache.readWorkflowStep(workflow.tenantId(), workflow.workflowId(), stepId)
            .orElseGet(() -> beginStep(workflow, stepId, input));
    }

    private WorkflowStepRow beginStep(WorkflowStateRow workflow, String stepId, Object input) {
        WorkflowStepRow existing = cache.readWorkflowStep(workflow.tenantId(), workflow.workflowId(), stepId)
            .orElse(null);
        if (existing != null && existing.completed()) {
            return existing;
        }
        Instant now = clock.instant();
        WorkflowStepRow step = new WorkflowStepRow(
            workflow.tenantId(), workflow.workflowId(), stepId, commandId(workflow.workflowId(), stepId),
            commandType(stepId), "[]", 120_000,
            "{\"max_attempts\":4,\"base_delay_ms\":1000,\"max_delay_ms\":32000}",
            compensation(stepId), "RUNNING", existing == null ? 1 : existing.attemptCount() + 1, toJson(input),
            existing == null ? null : existing.outputJson(), now, null, null, null
        );
        cache.upsertWorkflowStep(step);
        transitionWorkflow(workflow, "RUNNING", stepId, null, null, workflow.retryCount(), null);
        return step;
    }

    private void completeStep(WorkflowStepRow step, Object output) {
        cache.upsertWorkflowStep(new WorkflowStepRow(
            step.tenantId(), step.workflowId(), step.stepId(), step.commandId(), step.commandType(),
            step.expectedEventsJson(), step.timeoutMillis(), step.retryPolicyJson(), step.compensation(),
            "COMPLETED", step.attemptCount(), step.inputJson(), toJson(output),
            step.startedAt(), clock.instant(), null, null
        ));
    }

    private void retryStep(WorkflowStepRow step, Exception failure, Instant nextAttemptAt) {
        cache.upsertWorkflowStep(new WorkflowStepRow(
            step.tenantId(), step.workflowId(), step.stepId(), step.commandId(), step.commandType(),
            step.expectedEventsJson(), step.timeoutMillis(), step.retryPolicyJson(), step.compensation(),
            "RETRYABLE", step.attemptCount(), step.inputJson(), step.outputJson(), step.startedAt(), null,
            nextAttemptAt, safeMessage(failure)
        ));
    }

    private void failStep(WorkflowStepRow step, Exception failure) {
        cache.upsertWorkflowStep(new WorkflowStepRow(
            step.tenantId(), step.workflowId(), step.stepId(), step.commandId(), step.commandType(),
            step.expectedEventsJson(), step.timeoutMillis(), step.retryPolicyJson(), step.compensation(),
            "FAILED", step.attemptCount(), step.inputJson(), step.outputJson(), step.startedAt(), clock.instant(),
            null, safeMessage(failure)
        ));
    }

    private static String commandType(String stepId) {
        return switch (stepId) {
            case "DISCOVER" -> "DiscoverSourceResource";
            case "ACQUIRE" -> "AcquireSourceArtifact";
            case "INGEST" -> "ProcessAndPublishSourceVersion";
            default -> throw new IllegalArgumentException("Unknown ingestion workflow step: " + stepId);
        };
    }

    private static String compensation(String stepId) {
        return switch (stepId) {
            case "DISCOVER" -> "REVERSIBLE";
            case "ACQUIRE" -> "COMPENSATABLE";
            case "INGEST" -> "IRREVERSIBLE";
            default -> throw new IllegalArgumentException("Unknown ingestion workflow step: " + stepId);
        };
    }

    private WorkflowStateRow transitionWorkflow(
            WorkflowStateRow old, String state, String currentStep, String failureCode,
            String failureMessage, int retryCount, Instant nextAttemptAt) {
        Instant now = clock.instant();
        boolean terminal = "COMPLETED".equals(state) || "FAILED".equals(state)
            || "CANCELLED".equals(state) || "ESCALATED".equals(state);
        WorkflowStateRow updated = new WorkflowStateRow(
            old.tenantId(), old.workflowId(), old.workflowType(), old.workflowVersion(), old.operationId(),
            old.runId(), old.sourceId(), old.resourceId(), old.principalId(), old.principalType(),
            old.requestId(), old.idempotencyKey(), state, currentStep, old.createdAt(), now,
            old.startedAt() == null && "RUNNING".equals(state) ? now : old.startedAt(),
            terminal ? now : null, old.correlationId(), failureCode, failureMessage, retryCount, nextAttemptAt
        );
        cache.upsertWorkflowState(updated);
        return updated;
    }

    private WorkflowStateRow finishWorkflowFromRun(WorkflowStateRow workflow, IngestionRunRow run) {
        WorkflowStateRow latest = cache.readWorkflowState(workflow.tenantId(), workflow.workflowId()).orElse(workflow);
        String state = "CANCELLED".equals(run.status()) ? "CANCELLED"
            : "FAILED".equals(run.status()) || "QUARANTINED".equals(run.status()) ? "FAILED" : "COMPLETED";
        String code = null;
        String message = null;
        if (run.errorRef() != null) {
            var error = cache.readIngestionError(latest.tenantId(), UUID.fromString(run.errorRef())).orElse(null);
            if (error != null) {
                code = error.code();
                message = error.detail();
            }
        }
        return finishWorkflow(latest, state, latest.currentStep(), code, message);
    }

    private WorkflowStateRow finishWorkflow(
            WorkflowStateRow workflow, String state, String step, String failureCode, String failureMessage) {
        WorkflowStateRow latest = cache.readWorkflowState(workflow.tenantId(), workflow.workflowId()).orElse(workflow);
        return transitionWorkflow(latest, state, step, failureCode, failureMessage, latest.retryCount(), null);
    }

    private WorkflowStateRow ensureWorkflow(
            VerifiedIngestionContext context, String sourceId, String resourceId, OperationRow operation, UUID runId) {
        Instant now = clock.instant();
        WorkflowStateRow initial = new WorkflowStateRow(
            context.tenantId(), workflowId(operation.operationId()), WORKFLOW_TYPE, WORKFLOW_VERSION,
            operation.operationId(), runId, sourceId, resourceId, context.principalId(), context.principalType(),
            operation.requestId(), operation.idempotencyKey(), "RECEIVED", "DISCOVER", operation.createdAt(), now,
            null, null, operation.requestId() == null || operation.requestId().isBlank()
                ? operation.operationId().toString() : operation.requestId(), null, null, 0, null
        );
        return cache.createWorkflowStateIfAbsent(initial);
    }

    private void resumeWorkflow(WorkflowStateRow workflow) {
        VerifiedIngestionContext context = new VerifiedIngestionContext(
            workflow.tenantId(), workflow.principalId(), workflow.principalType());
        OperationRow operation = operations.recoverAccepted(
            context, workflow.operationId(), workflow.requestId(), workflow.idempotencyKey());
        SourceRow source = cache.readSource(workflow.tenantId(), workflow.sourceId()).orElse(null);
        if (source == null || !"ACTIVE".equals(source.status())) {
            IngestionRunRow failed = lifecycle.recordEmptyRun(
                context, workflow.runId(), workflow.operationId(), workflow.sourceId(),
                FixtureIngestionService.EmptyRunDisposition.FAILED_BEFORE_DISCOVERY,
                new IllegalStateException("Source is unavailable during workflow recovery"));
            finishWorkflowFromRun(workflow, failed);
            operations.completeFromRun(operation, failed);
            return;
        }
        OperationRow running = operations.markRunning(operation, 1);
        lifecycle.scheduleRun(context, workflow.runId(), workflow.operationId(), workflow.sourceId());
        process(context, source, workflow.sourceId(), workflow.resourceId(), workflow.runId(), running, workflow);
    }

    private static boolean retryableFailure(Exception failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof ContentIntegrityException || cause instanceof SourceRevisionConflictException
                    || cause instanceof org.synanton.ingestioncache.domain.SourceIdentityConflictException) {
                return false;
            }
            if (cause instanceof ContentStoreException || cause instanceof IOException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String errorDetail(String tenantId, String errorRef) {
        try {
            return cache.readIngestionError(tenantId, UUID.fromString(errorRef))
                .map(error -> error.detail()).orElse("Retryable ingestion failure");
        } catch (IllegalArgumentException malformedRef) {
            return "Retryable ingestion failure";
        }
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return failure.getClass().getSimpleName() + ": " + message.substring(0, Math.min(message.length(), 512));
    }

    private static String toJson(Object value) {
        try {
            return WORKFLOW_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize workflow checkpoint", e);
        }
    }

    private static <T> T readJson(String value, Class<T> type) {
        try {
            return WORKFLOW_MAPPER.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read workflow checkpoint", e);
        }
    }

    private static UUID workflowId(UUID operationId) {
        return UUID.nameUUIDFromBytes(("synflux-ingestion-workflow:" + operationId)
            .getBytes(StandardCharsets.UTF_8));
    }

    private static UUID commandId(UUID workflowId, String stepId) {
        return UUID.nameUUIDFromBytes(("synflux-workflow-command:" + workflowId + ":" + stepId)
            .getBytes(StandardCharsets.UTF_8));
    }

    private SourceRow requireActiveFilesystemSource(VerifiedIngestionContext context, String sourceId) {
        SourceRow source = cache.readSource(context.tenantId(), sourceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "source not found"));
        if (!"ACTIVE".equals(source.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "source is deleted");
        }
        if (!"file".equals(source.connectorType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fixture supports filesystem sources only");
        }
        return source;
    }

    public Submission deleteResource(
            VerifiedIngestionContext context, String sourceId, String resourceId,
            String requestId, String idempotencyKey) {
        SourceRow source = requireActiveFilesystemSource(context, sourceId);
        OperationRow operation = operations.accept(
            context, "DELETE /v1/sources/" + sourceId + "/resources", requestId, idempotencyKey);
        UUID runId = runId(operation.operationId());
        IngestionRunRow previousRun = cache.readIngestionRun(context.tenantId(), runId).orElse(null);
        if (previousRun != null && previousRun.completedAt() != null) {
            return new Submission(previousRun, operations.completeFromRun(operation, previousRun));
        }
        OperationRow running = operations.markRunning(operation, 1);
        if ("CANCELLED".equals(running.status())
                || operations.cancellationRequested(context.tenantId(), operation.operationId())) {
            IngestionRunRow cancelled = lifecycle.recordEmptyRun(
                context, runId, operation.operationId(), sourceId,
                FixtureIngestionService.EmptyRunDisposition.CANCELLED);
            return new Submission(cancelled, operations.completeFromRun(running, cancelled));
        }
        IngestionRunRow run = lifecycle.recordDeletion(
            context, runId, operation.operationId(), source.connectorId(), sourceId, resourceId,
            FixtureIngestionService.DeletionSignal.DELETED);
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
            cache.readIngestionRun(context.tenantId(), runId).ifPresent(run -> {
                if (run.completedAt() == null) {
                    lifecycle.recordEmptyRun(context, runId, operationId, run.sourceId(),
                        FixtureIngestionService.EmptyRunDisposition.CANCELLED);
                }
            });
            cache.readWorkflowState(context.tenantId(), workflowId(operationId)).ifPresent(workflow -> {
                if (!workflow.terminal()) {
                    finishWorkflow(workflow, "CANCELLED", workflow.currentStep(), null, null);
                }
            });
        }
        return cancelled;
    }

    private UUID publishThroughPipeline(
            AcquiredDocument acquired, StageContext context, UUID operationId, AtomicBoolean leaseLost)
            throws Exception {
        checkGuards(context.tenant(), operationId, leaseLost);
        ParsedDocument parsed = extractionStage.apply(acquired, context);
        checkGuards(context.tenant(), operationId, leaseLost);
        ChunkedDocument chunked = chunkStage.apply(parsed, context);
        checkGuards(context.tenant(), operationId, leaseLost);
        ChunkedDocument annotated = context.props().pipeline().annotationEnabled()
            ? annotationStage.apply(chunked, context) : chunked;
        checkGuards(context.tenant(), operationId, leaseLost);
        ChunkedDocument enriched = context.props().pipeline().enrichmentEnabled()
            ? enrichmentStage.apply(annotated, context) : annotated;
        checkGuards(context.tenant(), operationId, leaseLost);
        ChunkedDocument embedded = context.props().pipeline().embeddingEnabled()
            ? embeddingStage.apply(enriched, context) : enriched;
        checkGuards(context.tenant(), operationId, leaseLost);
        persistStage.apply(embedded, context);
        return acquired.contentRefId();
    }

    private void checkGuards(String tenantId, UUID operationId, AtomicBoolean leaseLost) {
        requireLease(leaseLost);
        if (operations.cancellationRequested(tenantId, operationId)) {
            throw new CancellationException("Operation cancellation requested");
        }
    }

    private IngestionRunRow scheduledRun(
            VerifiedIngestionContext context, String sourceId, UUID runId, OperationRow operation) {
        return lifecycle.scheduleRun(context, runId, operation.operationId(), sourceId);
    }

    private static UUID runId(UUID operationId) {
        return UUID.nameUUIDFromBytes(
            ("synflux-ingestion-run:" + operationId).getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    interface WorkflowStepObserver {
        void afterStepCompleted(String stepId);
    }

    private record RefCheckpoint(
        boolean found, String scheme, String uri, String mimeType, long sizeBytes, Long lastModifiedEpochMillis
    ) {
        static RefCheckpoint notFound() {
            return new RefCheckpoint(false, "", "", "", 0, null);
        }

        static RefCheckpoint from(ContentRef ref) {
            return new RefCheckpoint(true, ref.scheme(), ref.uri(), ref.mimeType(), ref.sizeBytes(),
                ref.lastModified() == null ? null : ref.lastModified().toEpochMilli());
        }

        ContentRef toContentRef() {
            return new ContentRef(scheme, uri, mimeType, sizeBytes,
                lastModifiedEpochMillis == null ? null : Instant.ofEpochMilli(lastModifiedEpochMillis));
        }
    }

    private record AcquisitionCheckpoint(UUID contentRefId, String sha256, String contentCacheReference) {}

    public record Submission(IngestionRunRow run, OperationRow operation) {}
}

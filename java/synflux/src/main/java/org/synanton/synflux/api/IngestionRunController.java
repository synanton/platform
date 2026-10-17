package org.synanton.synflux.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.synanton.common.context.RequestContext;
import org.synanton.common.context.RequestContextHolder;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.synflux.lifecycle.FixtureSourceWorkflow;
import org.synanton.synflux.security.TrustedRequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/v1/ingestion-runs")
public class IngestionRunController {

    private final FixtureSourceWorkflow workflow;
    private final IngestionCacheClient cache;

    public IngestionRunController(FixtureSourceWorkflow workflow, IngestionCacheClient cache) {
        this.workflow = workflow;
        this.cache = cache;
    }

    @PostMapping
    public ResponseEntity<RunSubmission> submit(
            @RequestBody SubmitIngestionRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        var context = TrustedRequestContext.requireIngestionContext();
        if (idempotencyKey != null && idempotencyKey.length() > 256) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key exceeds 256 characters");
        }
        String requestId = requestId();
        FixtureSourceWorkflow.Submission submission = workflow.submit(
            context, request.sourceId(), request.resourceId(), requestId, idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RunSubmission.from(submission.run()));
    }

    @GetMapping("/{runId}")
    public RunResource get(@PathVariable UUID runId) {
        String tenantId = TrustedRequestContext.requireIngestionContext().tenantId();
        IngestionRunRow run = cache.readIngestionRun(tenantId, runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ingestion run not found"));
        return RunResource.from(run);
    }

    @PostMapping("/{runId}/cancel")
    public RunResource cancel(@PathVariable UUID runId) {
        var context = TrustedRequestContext.requireIngestionContext();
        IngestionRunRow run = cache.readIngestionRun(context.tenantId(), runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ingestion run not found"));
        workflow.cancelOperation(context, run.operationId());
        IngestionRunRow updated = cache.readIngestionRun(context.tenantId(), runId).orElse(run);
        return RunResource.from(updated);
    }

    private static String requestId() {
        RequestContext context = RequestContextHolder.get();
        return context == null || context.traceId() == null ? UUID.randomUUID().toString() : context.traceId();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubmitIngestionRequest(String sourceId, String resourceId) {
        public SubmitIngestionRequest {
            if (sourceId == null || sourceId.isBlank() || resourceId == null || resourceId.isBlank()) {
                throw new IllegalArgumentException("source_id and resource_id are required");
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunSubmission(UUID runId, UUID operationId, String status) {
        public static RunSubmission from(IngestionRunRow run) {
            return new RunSubmission(run.runId(), run.operationId(), run.status());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunResource(
        UUID runId,
        UUID operationId,
        String sourceId,
        String status,
        java.time.Instant startedAt,
        java.time.Instant completedAt,
        SyncResultResource result,
        String errorRef
    ) {
        private static RunResource from(IngestionRunRow run) {
            return new RunResource(run.runId(), run.operationId(), run.sourceId(), run.status(), run.startedAt(),
                run.completedAt(), SyncResultResource.from(run.result()), run.errorRef());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SyncResultResource(
        int discovered,
        int changed,
        int unchanged,
        int deleted,
        int failed,
        int skipped,
        int quarantined,
        int cancelled
    ) {
        private static SyncResultResource from(org.synanton.ingestioncache.domain.SyncResultRow result) {
            return result == null ? null : new SyncResultResource(
                result.discovered(), result.changed(), result.unchanged(), result.deleted(), result.failed(),
                result.skipped(), result.quarantined(), result.cancelled()
            );
        }
    }
}

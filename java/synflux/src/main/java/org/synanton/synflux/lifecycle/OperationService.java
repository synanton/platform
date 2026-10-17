package org.synanton.synflux.lifecycle;

import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creates and reconciles the PAPI-3 public Operation linked to an ingestion run. */
@Service
public class OperationService {

    public static final String INGESTION_ENDPOINT = "POST /v1/ingestion-runs";

    private final IngestionCacheClient cache;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<>();

    public OperationService(IngestionCacheClient cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    public OperationRow accept(VerifiedIngestionContext context, String requestId, String idempotencyKey) {
        return accept(context, INGESTION_ENDPOINT, requestId, idempotencyKey);
    }

    public OperationRow accept(
            VerifiedIngestionContext context, String endpoint, String requestId, String idempotencyKey) {
        UUID candidateId = UUID.randomUUID();
        UUID operationId = idempotencyKey == null || idempotencyKey.isBlank()
            ? candidateId
            : cache.reserveOperationIdempotency(context.tenantId(), context.principalId(), endpoint,
                idempotencyKey, candidateId, clock.instant());
        var existing = cache.readOperation(context.tenantId(), operationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        OperationRow accepted = new OperationRow(
            context.tenantId(), operationId, "INGESTION", "ACCEPTED", context.principalId(),
            clock.instant(), null, null, 0, 0, null, null, requestId, idempotencyKey
        );
        if (cache.createOperationIfAbsent(accepted)) {
            return accepted;
        }
        return cache.readOperation(context.tenantId(), operationId)
            .orElseThrow(() -> new IllegalStateException("Operation creation lost without a readable row"));
    }

    public OperationRow markRunning(OperationRow operation, int total) {
        operation = cache.readOperation(operation.tenantId(), operation.operationId()).orElse(operation);
        if (cancellationRequested(operation.operationId())) {
            return operation;
        }
        if (!"ACCEPTED".equals(operation.status()) && !"QUEUED".equals(operation.status())) {
            return operation;
        }
        OperationRow running = new OperationRow(
            operation.tenantId(), operation.operationId(), operation.type(), "RUNNING",
            operation.requestedByPrincipalId(), operation.createdAt(), clock.instant(), null,
            0, total, null, null, operation.requestId(), operation.idempotencyKey()
        );
        cache.upsertOperation(running);
        return running;
    }

    public OperationRow cancel(String tenantId, UUID operationId) {
        OperationRow operation = cache.readOperation(tenantId, operationId)
            .orElseThrow(() -> new IllegalArgumentException("operation not found"));
        if ("SUCCEEDED".equals(operation.status()) || "FAILED".equals(operation.status())
                || "CANCELLED".equals(operation.status()) || "CANCEL_REQUESTED".equals(operation.status())
                || "EXPIRED".equals(operation.status())) {
            return operation;
        }
        if ("RUNNING".equals(operation.status())) {
            cancellationFlags.computeIfAbsent(operationId, ignored -> new AtomicBoolean()).set(true);
            OperationRow requested = new OperationRow(
                operation.tenantId(), operation.operationId(), operation.type(), "CANCEL_REQUESTED",
                operation.requestedByPrincipalId(), operation.createdAt(), operation.startedAt(), null,
                operation.progressCompleted(), operation.progressTotal(), null, null,
                operation.requestId(), operation.idempotencyKey()
            );
            cache.upsertOperation(requested);
            return requested;
        }
        OperationRow cancelled = new OperationRow(
            operation.tenantId(), operation.operationId(), operation.type(), "CANCELLED",
            operation.requestedByPrincipalId(), operation.createdAt(), operation.startedAt(), clock.instant(),
            operation.progressCompleted(), operation.progressTotal(), null, null,
            operation.requestId(), operation.idempotencyKey()
        );
        cache.upsertOperation(cancelled);
        return cancelled;
    }

    public boolean cancellationRequested(UUID operationId) {
        AtomicBoolean flag = cancellationFlags.get(operationId);
        return flag != null && flag.get();
    }

    public OperationRow completeFromRun(OperationRow operation, IngestionRunRow run) {
        String status;
        String outcome = null;
        UUID errorId = parseErrorId(run.errorRef());
        if ("FAILED".equals(run.status()) || "QUARANTINED".equals(run.status())) {
            status = "FAILED";
        } else if ("CANCELLED".equals(run.status())) {
            status = "CANCELLED";
            errorId = null;
        } else if ("COMPLETED".equals(run.status())) {
            if (run.result() != null && (run.result().failed() > 0 || run.result().quarantined() > 0)) {
                status = "SUCCEEDED";
                outcome = "PARTIAL";
            } else {
                status = "SUCCEEDED";
                outcome = "FULL";
            }
        } else if ("UNCHANGED".equals(run.status()) || "SKIPPED".equals(run.status())
                || "DELETED".equals(run.status())) {
            status = "SUCCEEDED";
            outcome = "FULL";
            errorId = null;
        } else {
            throw new IllegalArgumentException("IngestionRun is not terminal: " + run.status());
        }
        OperationRow completed = new OperationRow(
            operation.tenantId(), operation.operationId(), operation.type(), status,
            operation.requestedByPrincipalId(), operation.createdAt(),
            operation.startedAt() == null ? operation.createdAt() : operation.startedAt(), clock.instant(),
            run.result() == null ? 0 : run.result().entryCount(),
            Math.max(operation.progressTotal(), run.result() == null ? 0 : run.result().entryCount()),
            outcome, errorId, operation.requestId(), operation.idempotencyKey()
        );
        cache.upsertOperation(completed);
        return completed;
    }

    public OperationRow expireBeforeDiscovery(OperationRow operation) {
        if ("SUCCEEDED".equals(operation.status()) || "FAILED".equals(operation.status())
                || "CANCELLED".equals(operation.status()) || "EXPIRED".equals(operation.status())) {
            return operation;
        }
        OperationRow expired = new OperationRow(
            operation.tenantId(), operation.operationId(), operation.type(), "EXPIRED",
            operation.requestedByPrincipalId(), operation.createdAt(), operation.startedAt(), clock.instant(),
            operation.progressCompleted(), operation.progressTotal(), null, null,
            operation.requestId(), operation.idempotencyKey()
        );
        cache.upsertOperation(expired);
        return expired;
    }

    public OperationRow read(String tenantId, UUID operationId) {
        return cache.readOperation(tenantId, operationId).orElse(null);
    }

    private static UUID parseErrorId(String errorRef) {
        if (errorRef == null || errorRef.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(errorRef);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

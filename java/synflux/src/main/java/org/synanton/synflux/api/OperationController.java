package org.synanton.synflux.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionErrorRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.synanton.synflux.lifecycle.FixtureSourceWorkflow;
import org.synanton.synflux.security.TrustedRequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/operations")
public class OperationController {

    private final IngestionCacheClient cache;
    private final FixtureSourceWorkflow workflow;

    public OperationController(IngestionCacheClient cache, FixtureSourceWorkflow workflow) {
        this.cache = cache;
        this.workflow = workflow;
    }

    @GetMapping("/{operationId}")
    public OperationResource get(@PathVariable UUID operationId) {
        String tenantId = TrustedRequestContext.requireIngestionContext().tenantId();
        OperationRow operation = cache.readOperation(tenantId, operationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "operation not found"));
        IngestionErrorRow error = operation.errorId() == null
            ? null
            : cache.readIngestionError(tenantId, operation.errorId()).orElse(null);
        return OperationResource.from(operation, error);
    }

    @PostMapping("/{operationId}/cancel")
    public OperationResource cancel(@PathVariable UUID operationId) {
        String tenantId = TrustedRequestContext.requireIngestionContext().tenantId();
        if (cache.readOperation(tenantId, operationId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "operation not found");
        }
        OperationRow operation = workflow.cancelOperation(
            TrustedRequestContext.requireIngestionContext(), operationId);
        IngestionErrorRow error = operation.errorId() == null
            ? null
            : cache.readIngestionError(tenantId, operation.errorId()).orElse(null);
        return OperationResource.from(operation, error);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RequestedBy(String principalId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Progress(int completed, int total) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OperationResource(
        UUID operationId,
        String type,
        String status,
        RequestedBy requestedBy,
        String tenantId,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Progress progress,
        String outcome,
        Map<String, Object> error
    ) {
        private static OperationResource from(OperationRow row, IngestionErrorRow error) {
            Map<String, Object> problem = error == null ? null : Map.of(
                "type", error.type(),
                "title", error.title(),
                "status", error.status(),
                "detail", error.detail(),
                "instance", error.instance(),
                "code", error.code(),
                "retryable", error.retryable(),
                "request_id", error.requestId(),
                "details", Map.of()
            );
            return new OperationResource(
                row.operationId(), row.type(), row.status(), new RequestedBy(row.requestedByPrincipalId()),
                row.tenantId(), row.createdAt(), row.startedAt(), row.completedAt(),
                new Progress(row.progressCompleted(), row.progressTotal()), row.outcome(), problem
            );
        }
    }
}

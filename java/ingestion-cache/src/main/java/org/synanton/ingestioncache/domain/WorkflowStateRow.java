package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Durable orchestration state for one source-ingestion workflow. */
public record WorkflowStateRow(
    String tenantId,
    UUID workflowId,
    String workflowType,
    int workflowVersion,
    UUID operationId,
    UUID runId,
    String sourceId,
    String resourceId,
    String principalId,
    String principalType,
    String requestId,
    String idempotencyKey,
    String state,
    String currentStep,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt,
    String correlationId,
    String failureCode,
    String failureMessage,
    int retryCount,
    Instant nextAttemptAt
) {
    public boolean terminal() {
        return completedAt != null;
    }
}

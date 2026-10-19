package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Public operation state for a long-running ingestion request. */
public record OperationRow(
    String tenantId,
    UUID operationId,
    String type,
    String status,
    String requestedByPrincipalId,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt,
    int progressCompleted,
    int progressTotal,
    String outcome,
    UUID errorId,
    String requestId,
    String idempotencyKey,
    /** W2 open-typed details (JSON): feature_states, payload_descriptor, ... */
    String detailsJson
) {
    /** Compatibility for callers that carry no details. */
    public OperationRow(String tenantId, UUID operationId, String type, String status,
                        String requestedByPrincipalId, Instant createdAt, Instant startedAt,
                        Instant completedAt, int progressCompleted, int progressTotal, String outcome,
                        UUID errorId, String requestId, String idempotencyKey) {
        this(tenantId, operationId, type, status, requestedByPrincipalId, createdAt, startedAt,
                completedAt, progressCompleted, progressTotal, outcome, errorId, requestId,
                idempotencyKey, null);
    }
}

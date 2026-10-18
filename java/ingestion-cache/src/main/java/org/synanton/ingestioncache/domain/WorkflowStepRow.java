package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Durable command/checkpoint state for one step in a workflow execution. */
public record WorkflowStepRow(
    String tenantId,
    UUID workflowId,
    String stepId,
    UUID commandId,
    String commandType,
    String expectedEventsJson,
    long timeoutMillis,
    String retryPolicyJson,
    String compensation,
    String status,
    int attemptCount,
    String inputJson,
    String outputJson,
    Instant startedAt,
    Instant completedAt,
    Instant nextAttemptAt,
    String lastError
) {
    public boolean completed() {
        return completedAt != null;
    }
}

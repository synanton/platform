package org.synanton.gpu.gateway.idempotency;

import org.synanton.gpu.v1.ExecutionResponse;
import org.synanton.gpu.v1.ExecutionStatus;

import java.util.Optional;

// IdempotencyStore maps request_id → (execution_id, ExecutionResponse) to prevent
// duplicate GPU execution when the primary platform retries due to network timeouts.
//
// Critical invariant (fail-closed):
//   If the store is unhealthy or unreachable, implementations MUST throw an exception.
//   Callers MUST NOT allow Execute() to proceed without a successful idempotency check.
//   Passing through without the check creates a duplicate-execution window precisely
//   when the store is unavailable — the worst possible time.
public interface IdempotencyStore {

    // get returns the stored response for the given request_id if it exists.
    // Throws a runtime exception if the store is unavailable (fail-closed).
    Optional<ExecutionResponse> get(String requestId);

    // initiate creates a QUEUED entry for the given (requestId, executionId).
    // Throws if the store is unavailable or if requestId already exists (duplicate guard).
    void initiate(String requestId, String executionId);

    // complete stores the final ExecutionResponse for the given requestId.
    void complete(String requestId, ExecutionResponse response);

    // getByExecutionId retrieves the execution status keyed by execution_id (for GetStatus).
    Optional<ExecutionStatus> getByExecutionId(String executionId);
}

package org.synanton.gpu.gateway;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.gpu.gateway.auth.TenantAssertionValidator;
import org.synanton.gpu.gateway.dispatch.DirectDispatcher;
import org.synanton.gpu.gateway.dispatch.DispatchResult;
import org.synanton.gpu.gateway.dispatch.ExecutionDispatcher;
import org.synanton.gpu.gateway.idempotency.IdempotencyStore;
import org.synanton.gpu.gateway.idempotency.JdbcIdempotencyStore.DuplicateRequestIdException;
import org.synanton.gpu.gateway.idempotency.JdbcIdempotencyStore.IdempotencyStoreUnavailableException;
import org.synanton.gpu.gateway.metrics.GpuGatewayMetrics;
import org.synanton.gpu.v1.*;

import java.util.Optional;
import java.util.UUID;

// GpuExecutionServiceImpl implements the synanton.gpu.v1.GPUExecutionService gRPC contract.
//
// Ownership invariants upheld here:
//   - request_id is supplied by the primary platform; execution_id is generated here.
//   - tenant_id is an assertion validated against the calling service identity.
//   - Idempotency store is fail-closed: unavailability → 5xx, no pass-through.
//   - GPU execution state is never treated as business state.
//   - User-facing error rendering belongs to the primary platform; this class returns ErrorInfo only.
@Component
public class GpuExecutionServiceImpl extends GPUExecutionServiceGrpc.GPUExecutionServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(GpuExecutionServiceImpl.class);

    private final IdempotencyStore idempotencyStore;
    private final ExecutionDispatcher dispatcher;
    private final TenantAssertionValidator tenantValidator;
    private final GpuGatewayMetrics metrics;

    public GpuExecutionServiceImpl(
            IdempotencyStore idempotencyStore,
            ExecutionDispatcher dispatcher,
            TenantAssertionValidator tenantValidator,
            GpuGatewayMetrics metrics
    ) {
        this.idempotencyStore = idempotencyStore;
        this.dispatcher = dispatcher;
        this.tenantValidator = tenantValidator;
        this.metrics = metrics;
    }

    // ─── Execute ─────────────────────────────────────────────────────────────

    @Override
    public void execute(ExecutionRequest request, StreamObserver<ExecutionResponse> observer) {
        // 1. Field validation
        Status validationError = validateExecuteRequest(request);
        if (validationError != null) {
            observer.onError(validationError.asRuntimeException());
            return;
        }

        // 2. Tenant assertion validation
        TenantAssertionValidator.ValidationResult tenantCheck =
                tenantValidator.validate(request.getTenantId(), extractServiceIdentity());
        if (!tenantCheck.allowed()) {
            observer.onError(Status.PERMISSION_DENIED
                    .withDescription(tenantCheck.reason()).asRuntimeException());
            return;
        }

        String requestId = request.getRequestId();

        // 3. Idempotency check — fail-closed
        Optional<ExecutionResponse> cached;
        try {
            cached = idempotencyStore.get(requestId);
        } catch (IdempotencyStoreUnavailableException e) {
            log.error("Idempotency store unavailable; blocking Execute for request_id={}", requestId, e);
            metrics.setIdempotencyStoreHealthy(false);
            observer.onError(Status.UNAVAILABLE
                    .withDescription("idempotency store unavailable").asRuntimeException());
            return;
        }
        metrics.setIdempotencyStoreHealthy(true);

        if (cached.isPresent()) {
            log.debug("Idempotency hit for request_id={}", requestId);
            metrics.recordIdempotencyHit(request.getModel());
            observer.onNext(cached.get());
            observer.onCompleted();
            return;
        }

        // 4. Generate Gateway-owned execution_id
        String executionId = UUID.randomUUID().toString();

        // 5. Record QUEUED state — fail-closed; duplicate request_id is a guard, not an error path
        try {
            idempotencyStore.initiate(requestId, executionId);
        } catch (DuplicateRequestIdException e) {
            // Race: another thread won the initiation. Re-read and return the cached response.
            Optional<ExecutionResponse> raceCached = idempotencyStore.get(requestId);
            if (raceCached.isPresent()) {
                metrics.recordIdempotencyHit(request.getModel());
                observer.onNext(raceCached.get());
                observer.onCompleted();
                return;
            }
            // Extremely unlikely: the winner hasn't completed yet. Return UNAVAILABLE to trigger retry.
            observer.onError(Status.UNAVAILABLE.withDescription("concurrent initiation; retry").asRuntimeException());
            return;
        } catch (IdempotencyStoreUnavailableException e) {
            log.error("Failed to initiate idempotency record for request_id={}", requestId, e);
            metrics.setIdempotencyStoreHealthy(false);
            observer.onError(Status.UNAVAILABLE.withDescription("failed to initiate execution record").asRuntimeException());
            return;
        }

        log.info("Dispatching execution_id={} model={} op={} request_id={}",
                executionId, request.getModel(), request.getOperation(), requestId);

        // 6. Dispatch — Gateway does NOT cancel this if the gRPC stream closes
        DispatchResult result;
        try {
            result = dispatcher.dispatch(request, executionId);
        } catch (Exception e) {
            log.error("Unexpected dispatch error for execution_id={}", executionId, e);
            result = DispatchResult.failed("Internal dispatch error: " + e.getMessage());
        }

        // 7. Build response
        ExecutionResponse response = buildResponse(request, executionId, result);

        // 8. Persist result for future idempotency lookups
        try {
            idempotencyStore.complete(requestId, response);
        } catch (Exception e) {
            log.warn("Failed to persist result for request_id={}; next retry will re-execute", requestId, e);
        }

        metrics.recordExecution(
                request.getModel(),
                request.getModelVersion(),
                result.success() ? "success" : "failed",
                result.durationMs()
        );

        observer.onNext(response);
        observer.onCompleted();
    }

    // ─── Cancel ──────────────────────────────────────────────────────────────

    @Override
    public void cancel(CancelRequest request, StreamObserver<CancelResponse> observer) {
        if (request.getExecutionId().isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("execution_id is required").asRuntimeException());
            return;
        }

        // Cancellation is best-effort. Look up the execution to determine current state.
        Optional<ExecutionStatus> status;
        try {
            status = idempotencyStore.getByExecutionId(request.getExecutionId());
        } catch (Exception e) {
            log.error("Failed to look up execution_id={} for cancellation", request.getExecutionId(), e);
            observer.onError(Status.UNAVAILABLE.withDescription("idempotency store unavailable").asRuntimeException());
            return;
        }

        CancellationOutcome outcome;
        if (status.isEmpty()) {
            outcome = CancellationOutcome.NOT_APPLICABLE;
        } else {
            ExecutionState state = status.get().getState();
            outcome = switch (state) {
                case QUEUED, RUNNING -> CancellationOutcome.ACCEPTED;
                case SUCCESS, FAILED, CANCELLED, TIMEOUT -> CancellationOutcome.COMPLETED;
                default -> CancellationOutcome.NOT_APPLICABLE;
            };
        }

        // Best-effort: no distributed transaction; primary platform owns business rollback.
        log.info("Cancel execution_id={} outcome={}", request.getExecutionId(), outcome);
        metrics.recordCancellation(
                status.map(s -> "unknown").orElse("unknown"),
                outcome.name()
        );

        observer.onNext(CancelResponse.newBuilder()
                .setExecutionId(request.getExecutionId())
                .setOutcome(outcome)
                .build());
        observer.onCompleted();
    }

    // ─── GetStatus ───────────────────────────────────────────────────────────

    @Override
    public void getStatus(GetStatusRequest request, StreamObserver<ExecutionStatus> observer) {
        if (request.getExecutionId().isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("execution_id is required").asRuntimeException());
            return;
        }

        Optional<ExecutionStatus> status;
        try {
            status = idempotencyStore.getByExecutionId(request.getExecutionId());
        } catch (Exception e) {
            log.error("Failed to retrieve status for execution_id={}", request.getExecutionId(), e);
            observer.onError(Status.UNAVAILABLE.withDescription("idempotency store unavailable").asRuntimeException());
            return;
        }

        if (status.isEmpty()) {
            observer.onError(Status.NOT_FOUND
                    .withDescription("execution_id not found: " + request.getExecutionId()).asRuntimeException());
            return;
        }

        observer.onNext(status.get());
        observer.onCompleted();
    }

    // ─── GetCapacity ─────────────────────────────────────────────────────────

    @Override
    public void getCapacity(GetCapacityRequest request, StreamObserver<CapacityResponse> observer) {
        // Advisory only — does not reserve capacity.
        CapacityResponse response = dispatcher.capacity(request);
        observer.onNext(response);
        observer.onCompleted();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Status validateExecuteRequest(ExecutionRequest req) {
        if (req.getRequestId().isBlank())
            return Status.INVALID_ARGUMENT.withDescription("request_id is required");
        if (req.getRequestId().length() > 255)
            return Status.INVALID_ARGUMENT.withDescription("request_id exceeds 255 chars");
        if (req.getTenantId().isBlank())
            return Status.INVALID_ARGUMENT.withDescription("tenant_id is required");
        if (req.getTenantId().length() > 255)
            return Status.INVALID_ARGUMENT.withDescription("tenant_id exceeds 255 chars");
        if (req.getModel().isBlank())
            return Status.INVALID_ARGUMENT.withDescription("model is required");
        if (req.getModel().length() > 255)
            return Status.INVALID_ARGUMENT.withDescription("model exceeds 255 chars");
        if (req.getModelVersion().isBlank())
            return Status.INVALID_ARGUMENT.withDescription("model_version is required");
        if (req.getModelVersion().length() > 128)
            return Status.INVALID_ARGUMENT.withDescription("model_version exceeds 128 chars");
        if (req.getOperation() == Operation.OPERATION_UNSPECIFIED)
            return Status.INVALID_ARGUMENT.withDescription("operation must not be OPERATION_UNSPECIFIED");
        if (req.getPayload().size() > 4 * 1024 * 1024)
            return Status.INVALID_ARGUMENT.withDescription("payload exceeds 4 MB");
        return null;
    }

    private ExecutionResponse buildResponse(ExecutionRequest request, String executionId, DispatchResult result) {
        ExecutionResponse.Builder builder = ExecutionResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setExecutionId(executionId);

        UsageReport usage = UsageReport.newBuilder()
                .setDurationMs(result.durationMs())
                .setModel(request.getModel())
                .setModelVersion(request.getModelVersion())
                .setInputTokens(result.inputTokens())
                .setOutputTokens(result.outputTokens())
                .setOutcome(result.success() ? "success" : "failed")
                .build();

        if (result.success()) {
            builder.setState(ExecutionState.SUCCESS)
                    .setResult(com.google.protobuf.ByteString.copyFrom(result.result()))
                    .setUsage(usage);
        } else {
            ErrorInfo error = ErrorInfo.newBuilder()
                    .setReason(ErrorReason.EXECUTION_FAILED)
                    .setMessage(result.errorMessage() != null ? result.errorMessage() : "execution failed")
                    .setRetryable(false)
                    .build();
            builder.setState(ExecutionState.FAILED)
                    .setError(error)
                    .setUsage(usage);
        }

        return builder.build();
    }

    private String extractServiceIdentity() {
        // Production: extract CN from mTLS peer certificate via gRPC context.
        // Initial implementation: returns a placeholder; mTLS is enforced at transport layer.
        return "primary-platform";
    }
}

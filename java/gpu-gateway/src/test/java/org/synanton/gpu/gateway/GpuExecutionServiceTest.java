package org.synanton.gpu.gateway;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.gpu.gateway.auth.TenantAssertionValidator;
import org.synanton.gpu.gateway.dispatch.DirectDispatcher;
import org.synanton.gpu.gateway.dispatch.DispatchResult;
import org.synanton.gpu.gateway.idempotency.IdempotencyStore;
import org.synanton.gpu.gateway.idempotency.JdbcIdempotencyStore.IdempotencyStoreUnavailableException;
import org.synanton.gpu.gateway.metrics.GpuGatewayMetrics;
import org.synanton.gpu.v1.*;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GpuExecutionServiceTest {

    @Mock IdempotencyStore idempotencyStore;
    @Mock DirectDispatcher dispatcher;
    @Mock GpuGatewayMetrics metrics;

    private GpuExecutionServiceImpl service;
    private GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stub;
    private GrpcCleanupRule grpcCleanup;

    @BeforeEach
    void setUp() throws IOException {
        TenantAssertionValidator tenantValidator = new TenantAssertionValidator();
        service = new GpuExecutionServiceImpl(idempotencyStore, dispatcher, tenantValidator, metrics);

        grpcCleanup = new GrpcCleanupRule();
        String serverName = InProcessServerBuilder.generateName();
        grpcCleanup.register(
                InProcessServerBuilder.forName(serverName)
                        .directExecutor()
                        .addService(service)
                        .build()
                        .start()
        );
        stub = GPUExecutionServiceGrpc.newBlockingStub(
                grpcCleanup.register(InProcessChannelBuilder.forName(serverName).directExecutor().build())
        );
    }

    // ─── Execute: validation ──────────────────────────────────────────────────

    @Test
    void execute_missingRequestId_returnsInvalidArgument() {
        ExecutionRequest req = ExecutionRequest.newBuilder()
                .setTenantId("t1").setModel("llama3").setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .build();
        assertThatThrownBy(() -> stub.execute(req))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void execute_missingModel_returnsInvalidArgument() {
        ExecutionRequest req = ExecutionRequest.newBuilder()
                .setRequestId("req-1").setTenantId("t1").setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .build();
        assertThatThrownBy(() -> stub.execute(req))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void execute_unspecifiedOperation_returnsInvalidArgument() {
        ExecutionRequest req = ExecutionRequest.newBuilder()
                .setRequestId("req-1").setTenantId("t1").setModel("llama3").setModelVersion("1.0")
                .setOperation(Operation.OPERATION_UNSPECIFIED)
                .build();
        assertThatThrownBy(() -> stub.execute(req))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    // ─── Execute: idempotency ─────────────────────────────────────────────────

    @Test
    void execute_idempotencyHit_returnsStoredResponse() {
        ExecutionResponse stored = ExecutionResponse.newBuilder()
                .setRequestId("req-1").setExecutionId("exec-cached")
                .setState(ExecutionState.SUCCESS)
                .build();
        when(idempotencyStore.get("req-1")).thenReturn(Optional.of(stored));

        ExecutionResponse response = stub.execute(validRequest("req-1"));

        assertThat(response.getExecutionId()).isEqualTo("exec-cached");
        assertThat(response.getState()).isEqualTo(ExecutionState.SUCCESS);
        verify(dispatcher, never()).dispatch(any(), anyString());
        verify(metrics).recordIdempotencyHit("llama3");
    }

    @Test
    void execute_idempotencyStoreUnavailable_returnsUnavailable() {
        when(idempotencyStore.get(anyString())).thenThrow(
                new IdempotencyStoreUnavailableException("db down", new RuntimeException()));

        assertThatThrownBy(() -> stub.execute(validRequest("req-1")))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAVAILABLE));

        verify(dispatcher, never()).dispatch(any(), anyString());
        verify(metrics).setIdempotencyStoreHealthy(false);
    }

    // ─── Execute: successful dispatch ────────────────────────────────────────

    @Test
    void execute_newRequest_dispatchesAndReturnsSuccess() {
        when(idempotencyStore.get("req-new")).thenReturn(Optional.empty());
        doNothing().when(idempotencyStore).initiate(anyString(), anyString());
        when(dispatcher.dispatch(any(), anyString()))
                .thenReturn(DispatchResult.ok("{\"answer\":\"42\"}".getBytes(), 150, 10, 5));

        ExecutionResponse response = stub.execute(validRequest("req-new"));

        assertThat(response.getState()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(response.getRequestId()).isEqualTo("req-new");
        assertThat(response.getExecutionId()).isNotBlank();
        verify(idempotencyStore).complete(eq("req-new"), any());
        verify(metrics).recordExecution(eq("llama3"), eq("1.0"), eq("success"), anyLong());
    }

    @Test
    void execute_dispatchFailed_returnsFailedState() {
        when(idempotencyStore.get("req-fail")).thenReturn(Optional.empty());
        doNothing().when(idempotencyStore).initiate(anyString(), anyString());
        when(dispatcher.dispatch(any(), anyString())).thenReturn(DispatchResult.failed("vLLM HTTP 503"));

        ExecutionResponse response = stub.execute(validRequest("req-fail"));

        assertThat(response.getState()).isEqualTo(ExecutionState.FAILED);
        assertThat(response.getError().getReason()).isEqualTo(ErrorReason.EXECUTION_FAILED);
        assertThat(response.getError().isRetryable()).isFalse();
    }

    // ─── GetStatus ────────────────────────────────────────────────────────────

    @Test
    void getStatus_missingExecutionId_returnsInvalidArgument() {
        assertThatThrownBy(() -> stub.getStatus(GetStatusRequest.newBuilder().build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void getStatus_notFound_returnsNotFound() {
        when(idempotencyStore.getByExecutionId("exec-unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stub.getStatus(
                GetStatusRequest.newBuilder().setExecutionId("exec-unknown").build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void getStatus_found_returnsStatus() {
        ExecutionStatus stored = ExecutionStatus.newBuilder()
                .setRequestId("req-1").setExecutionId("exec-1")
                .setState(ExecutionState.SUCCESS)
                .build();
        when(idempotencyStore.getByExecutionId("exec-1")).thenReturn(Optional.of(stored));

        ExecutionStatus status = stub.getStatus(GetStatusRequest.newBuilder().setExecutionId("exec-1").build());

        assertThat(status.getState()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(status.getRequestId()).isEqualTo("req-1");
    }

    // ─── Cancel ───────────────────────────────────────────────────────────────

    @Test
    void cancel_missingExecutionId_returnsInvalidArgument() {
        assertThatThrownBy(() -> stub.cancel(CancelRequest.newBuilder().build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void cancel_notFound_returnsNotApplicable() {
        when(idempotencyStore.getByExecutionId("exec-404")).thenReturn(Optional.empty());

        CancelResponse response = stub.cancel(CancelRequest.newBuilder().setExecutionId("exec-404").build());

        assertThat(response.getOutcome()).isEqualTo(CancellationOutcome.NOT_APPLICABLE);
    }

    @Test
    void cancel_runningExecution_returnsAccepted() {
        ExecutionStatus running = ExecutionStatus.newBuilder()
                .setExecutionId("exec-run").setState(ExecutionState.RUNNING).build();
        when(idempotencyStore.getByExecutionId("exec-run")).thenReturn(Optional.of(running));

        CancelResponse response = stub.cancel(CancelRequest.newBuilder().setExecutionId("exec-run").build());

        assertThat(response.getOutcome()).isEqualTo(CancellationOutcome.ACCEPTED);
    }

    @Test
    void cancel_completedExecution_returnsCompleted() {
        ExecutionStatus done = ExecutionStatus.newBuilder()
                .setExecutionId("exec-done").setState(ExecutionState.SUCCESS).build();
        when(idempotencyStore.getByExecutionId("exec-done")).thenReturn(Optional.of(done));

        CancelResponse response = stub.cancel(CancelRequest.newBuilder().setExecutionId("exec-done").build());

        assertThat(response.getOutcome()).isEqualTo(CancellationOutcome.COMPLETED);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private ExecutionRequest validRequest(String requestId) {
        return ExecutionRequest.newBuilder()
                .setRequestId(requestId)
                .setTenantId("tenant-acme")
                .setModel("llama3")
                .setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(com.google.protobuf.ByteString.copyFromUtf8("{\"messages\":[]}"))
                .build();
    }
}

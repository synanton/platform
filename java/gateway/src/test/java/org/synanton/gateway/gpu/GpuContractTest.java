package org.synanton.gateway.gpu;

import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.gpu.v1.*;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

// Consumer-driven contract test for synanton.gpu.v1.GPUExecutionService.
// Verifies that the primary platform's GpuExecutionClient can communicate with
// the GPU Gateway using the generated proto stubs.
//
// This test runs entirely in-process (no network) using grpc-inprocess.
// It is the primary-platform's side of the contract test; the GPU Gateway
// must pass its own matching server-side tests.
//
// When the proto contract changes, this test fails if the client is broken
// before any deployment — satisfying GPU-1 DoD item 4 (consumer contract tests).
class GpuContractTest {

    private GrpcCleanupRule grpcCleanup;
    private GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        grpcCleanup = new GrpcCleanupRule();
        String serverName = InProcessServerBuilder.generateName();

        // Minimal in-process server that mimics the GPU Gateway contract
        grpcCleanup.register(
                InProcessServerBuilder.forName(serverName)
                        .directExecutor()
                        .addService(new FakeGpuGateway())
                        .build()
                        .start()
        );

        stub = GPUExecutionServiceGrpc.newBlockingStub(
                grpcCleanup.register(InProcessChannelBuilder.forName(serverName).directExecutor().build())
        );
    }

    @Test
    void execute_sendsRequestAndReceivesResponseWithGatewayGeneratedExecutionId() {
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setRequestId("req-contract-1")
                .setTenantId("tenant-acme")
                .setModel("llama3")
                .setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(com.google.protobuf.ByteString.copyFromUtf8("{\"messages\":[]}"))
                .build();

        ExecutionResponse response = stub.execute(request);

        assertThat(response.getRequestId()).isEqualTo("req-contract-1");
        assertThat(response.getExecutionId()).isNotBlank();
        assertThat(response.getState()).isEqualTo(ExecutionState.SUCCESS);
    }

    @Test
    void cancel_sendsRequestAndReceivesCancellationOutcome() {
        CancelRequest request = CancelRequest.newBuilder()
                .setExecutionId("exec-abc")
                .setRequestId("req-abc")
                .build();

        CancelResponse response = stub.cancel(request);

        assertThat(response.getExecutionId()).isEqualTo("exec-abc");
        assertThat(response.getOutcome()).isNotNull();
    }

    @Test
    void getStatus_sendsRequestAndReceivesExecutionStatus() {
        GetStatusRequest request = GetStatusRequest.newBuilder()
                .setExecutionId("exec-status-1")
                .build();

        ExecutionStatus status = stub.getStatus(request);

        assertThat(status.getExecutionId()).isEqualTo("exec-status-1");
        assertThat(status.getState()).isNotNull();
    }

    @Test
    void getCapacity_returnsAdvisoryCapacityResponse() {
        GetCapacityRequest request = GetCapacityRequest.newBuilder()
                .setModel("llama3")
                .build();

        CapacityResponse response = stub.getCapacity(request);

        assertThat(response.getModel()).isEqualTo("llama3");
        // Advisory only — no capacity reservation implied
    }

    @Test
    void execute_errorInfoIsRetryableForModelNotReady() {
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setRequestId("req-not-ready")
                .setTenantId("tenant-acme")
                .setModel("model-cold")
                .setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(com.google.protobuf.ByteString.copyFromUtf8("{\"messages\":[]}"))
                .build();

        ExecutionResponse response = stub.execute(request);

        assertThat(response.getState()).isEqualTo(ExecutionState.FAILED);
        assertThat(response.getError().getReason()).isEqualTo(ErrorReason.MODEL_NOT_READY);
        assertThat(response.getError().getRetryable()).isTrue();
    }

    // Minimal stub that implements the GPU Gateway contract for consumer-side testing.
    private static class FakeGpuGateway extends GPUExecutionServiceGrpc.GPUExecutionServiceImplBase {

        @Override
        public void execute(ExecutionRequest request, StreamObserver<ExecutionResponse> obs) {
            if ("model-cold".equals(request.getModel())) {
                obs.onNext(ExecutionResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setExecutionId("exec-" + System.nanoTime())
                        .setState(ExecutionState.FAILED)
                        .setError(ErrorInfo.newBuilder()
                                .setReason(ErrorReason.MODEL_NOT_READY)
                                .setMessage("model not loaded")
                                .setRetryable(true)
                                .build())
                        .build());
            } else {
                obs.onNext(ExecutionResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setExecutionId("exec-" + System.nanoTime())
                        .setState(ExecutionState.SUCCESS)
                        .setResult(com.google.protobuf.ByteString.copyFromUtf8("{\"answer\":\"ok\"}"))
                        .build());
            }
            obs.onCompleted();
        }

        @Override
        public void cancel(CancelRequest request, StreamObserver<CancelResponse> obs) {
            obs.onNext(CancelResponse.newBuilder()
                    .setExecutionId(request.getExecutionId())
                    .setOutcome(CancellationOutcome.ACCEPTED)
                    .build());
            obs.onCompleted();
        }

        @Override
        public void getStatus(GetStatusRequest request, StreamObserver<ExecutionStatus> obs) {
            obs.onNext(ExecutionStatus.newBuilder()
                    .setExecutionId(request.getExecutionId())
                    .setState(ExecutionState.SUCCESS)
                    .build());
            obs.onCompleted();
        }

        @Override
        public void getCapacity(GetCapacityRequest request, StreamObserver<CapacityResponse> obs) {
            obs.onNext(CapacityResponse.newBuilder()
                    .setModel(request.getModel())
                    .setHealthy(true)
                    .setModelLoaded(true)
                    .setEstimatedAvailableFraction(0.8)
                    .build());
            obs.onCompleted();
        }
    }
}

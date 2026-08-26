package org.synanton.extraction.client;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import synanton.extraction.v1.DocumentElement;
import synanton.extraction.v1.DocumentElementType;
import synanton.extraction.v1.DocumentPayload;
import synanton.extraction.v1.ExtractionItemStatus;
import synanton.extraction.v1.ExtractionOperation;
import synanton.extraction.v1.ExtractionResult;
import synanton.extraction.v1.ExtractionServiceGrpc;
import synanton.extraction.v1.ExtractionStatus;
import synanton.extraction.v1.GetOperationsRequest;
import synanton.extraction.v1.GetOperationsResponse;
import synanton.extraction.v1.GetResultRequest;
import synanton.extraction.v1.SubmitExtractionRequest;
import synanton.extraction.v1.StructuredPayload;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionPlaneClientTest {

    private static final String TENANT = "demo";
    private static final String OPERATION_ID = "op-1";

    private Server server;
    private ManagedChannel channel;
    private ExtractionPlaneClient client;
    private SimpleMeterRegistry meterRegistry;
    private MockExtractionService mockService;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = InProcessServerBuilder.generateName();
        mockService = new MockExtractionService();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(mockService)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

        meterRegistry = new SimpleMeterRegistry();
        ExtractionClientProperties props = new ExtractionClientProperties(
                true, "in-process", "sync", 5, 1, "local-tika", "NORMAL");
        client = new ExtractionPlaneClient(props, new ExtractionClientMetrics(meterRegistry), channel);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
        meterRegistry.close();
    }

    @Test
    void shouldExtractSyncAndRecordMetrics() {
        mockService.syncResult = completedResult("structured text");

        ExtractionResult result = client.extractSync(sampleRequest());

        assertThat(result.getFlattenedText()).isEqualTo("structured text");
        assertThat(meterRegistry.get("extraction_client_requests_total")
                .tag("mode", "sync")
                .tag("outcome", "completed")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void shouldPollAsyncOperationUntilComplete() {
        ExtractionClientProperties asyncProps = new ExtractionClientProperties(
                true, "in-process", "async", 10, 1, "local-tika", "NORMAL");
        client = new ExtractionPlaneClient(asyncProps, new ExtractionClientMetrics(meterRegistry), channel);

        mockService.asyncTerminalAfterPolls = 1;
        mockService.asyncResult = completedResult("async structured text");

        ExtractionResult result = client.extract(sampleRequest());

        assertThat(result.getFlattenedText()).isEqualTo("async structured text");
        assertThat(mockService.submitCount.get()).isEqualTo(1);
        assertThat(mockService.getOperationsCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldReconcileSubmitTimeoutWithIdempotentResubmit() {
        ExtractionClientProperties asyncProps = new ExtractionClientProperties(
                true, "in-process", "async", 10, 1, "local-tika", "NORMAL");
        client = new ExtractionPlaneClient(asyncProps, new ExtractionClientMetrics(meterRegistry), channel);

        mockService.failFirstSubmitWithTimeout = true;
        mockService.asyncTerminalAfterPolls = 0;
        mockService.asyncResult = completedResult("reconciled text");

        ExtractionResult result = client.extract(sampleRequest());

        assertThat(result.getFlattenedText()).isEqualTo("reconciled text");
        assertThat(mockService.submitCount.get()).isEqualTo(2);
    }

    private static SubmitExtractionRequest sampleRequest() {
        return SubmitExtractionRequest.newBuilder()
                .setTenantId(TENANT)
                .setIdempotencyKey("idem-1")
                .build();
    }

    private static ExtractionResult completedResult(String text) {
        DocumentPayload payload = DocumentPayload.newBuilder()
                .addElements(DocumentElement.newBuilder()
                        .setType(DocumentElementType.ELEMENT_PARAGRAPH)
                        .setText(text)
                        .build())
                .setFlattenedText(text)
                .build();
        return ExtractionResult.newBuilder()
                .setOperationId(OPERATION_ID)
                .setStatus(ExtractionStatus.STATUS_COMPLETED)
                .setFlattenedText(text)
                .setPayload(StructuredPayload.newBuilder()
                        .setInlineContent(payload.toByteString())
                        .build())
                .build();
    }

    private static final class MockExtractionService extends ExtractionServiceGrpc.ExtractionServiceImplBase {
        ExtractionResult syncResult;
        ExtractionResult asyncResult;
        int asyncTerminalAfterPolls = 0;
        boolean failFirstSubmitWithTimeout = false;

        final AtomicInteger submitCount = new AtomicInteger();
        final AtomicInteger getOperationsCount = new AtomicInteger();

        @Override
        public void extractSync(SubmitExtractionRequest request, StreamObserver<ExtractionResult> responseObserver) {
            responseObserver.onNext(syncResult);
            responseObserver.onCompleted();
        }

        @Override
        public void submitExtraction(SubmitExtractionRequest request,
                                     StreamObserver<ExtractionOperation> responseObserver) {
            int attempt = submitCount.incrementAndGet();
            if (failFirstSubmitWithTimeout && attempt == 1) {
                responseObserver.onError(io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException());
                return;
            }
            ExtractionOperation queued = ExtractionOperation.newBuilder()
                    .setOperationId(OPERATION_ID)
                    .setTenantId(request.getTenantId())
                    .setStatus(ExtractionStatus.STATUS_QUEUED)
                    .build();
            responseObserver.onNext(queued);
            responseObserver.onCompleted();
        }

        @Override
        public void getOperations(GetOperationsRequest request,
                                  StreamObserver<GetOperationsResponse> responseObserver) {
            int poll = getOperationsCount.incrementAndGet();
            ExtractionStatus status = poll > asyncTerminalAfterPolls
                    ? ExtractionStatus.STATUS_COMPLETED
                    : ExtractionStatus.STATUS_RUNNING;
            ExtractionOperation operation = ExtractionOperation.newBuilder()
                    .setOperationId(OPERATION_ID)
                    .setTenantId(request.getTenantId())
                    .setStatus(status)
                    .addItems(ExtractionItemStatus.newBuilder()
                            .setItemIndex(0)
                            .setContentRefId("ref-1")
                            .setStatus(status)
                            .build())
                    .build();
            responseObserver.onNext(GetOperationsResponse.newBuilder().addOperations(operation).build());
            responseObserver.onCompleted();
        }

        @Override
        public void getResult(GetResultRequest request, StreamObserver<ExtractionResult> responseObserver) {
            responseObserver.onNext(asyncResult);
            responseObserver.onCompleted();
        }
    }
}

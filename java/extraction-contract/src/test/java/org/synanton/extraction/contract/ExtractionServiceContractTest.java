package org.synanton.extraction.contract;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import synanton.extraction.v1.ExtractionOperation;
import synanton.extraction.v1.ExtractionResult;
import synanton.extraction.v1.ExtractionServiceGrpc;
import synanton.extraction.v1.ExtractionStatus;
import synanton.extraction.v1.FeatureState;
import synanton.extraction.v1.GetOperationsRequest;
import synanton.extraction.v1.GetOperationsResponse;
import synanton.extraction.v1.GetResultRequest;
import synanton.extraction.v1.ListCompletedOperationsRequest;
import synanton.extraction.v1.ListCompletedOperationsResponse;
import synanton.extraction.v1.ObjectReference;
import synanton.extraction.v1.PayloadDescriptor;
import synanton.extraction.v1.PriorityClass;
import synanton.extraction.v1.SerializationFormat;
import synanton.extraction.v1.StructuredPayload;
import synanton.extraction.v1.SubmitExtractionRequest;
import synanton.extraction.v1.ExtractionRequestItem;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-driven contract test (SCEP-1 deliverable 7).
 *
 * <p>Exercises the platform's side of {@code synanton.extraction.v1} against an in-process mock
 * server, so client compatibility can be verified without the extraction plane existing. The mock
 * stands in for the plane; what is under test is that the generated stubs and the contract's
 * semantics hold together across a real RPC boundary.
 */
class ExtractionServiceContractTest {

    private static final String TENANT = "acme-legal";
    private static final String SHA = "b".repeat(64);

    private Server server;
    private ManagedChannel channel;
    private ExtractionServiceGrpc.ExtractionServiceBlockingStub client;
    private MockExtractionPlane plane;

    @BeforeEach
    void startServer() throws Exception {
        String name = InProcessServerBuilder.generateName();
        plane = new MockExtractionPlane();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(plane)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = ExtractionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stopServer() throws Exception {
        channel.shutdownNow();
        server.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    private static SubmitExtractionRequest submitRequest(String idempotencyKey) {
        return SubmitExtractionRequest.newBuilder()
                .setTenantId(TENANT)
                .setIdempotencyKey(idempotencyKey)
                .setPriorityClass(PriorityClass.PRIORITY_NORMAL)
                .setItem(ExtractionRequestItem.newBuilder()
                        .setContentRefId("content-pdf-001")
                        .setMediaType("application/pdf")
                        .setSource(ObjectReference.newBuilder()
                                .setBucket("tenant-content")
                                .setKey("documents/contract.pdf")
                                .setSha256(SHA)
                                .setSizeBytes(4096))
                        .putBusinessTags("department", "legal"))
                .build();
    }

    @Test
    void shouldReturnAnOperationHandleOnSubmit() {
        ExtractionOperation operation = client.submitExtraction(submitRequest("job-1"));

        assertThat(operation.getOperationId()).isNotBlank();
        assertThat(operation.getTenantId()).isEqualTo(TENANT);
        assertThat(operation.getStatus()).isEqualTo(ExtractionStatus.STATUS_ACCEPTED);
    }

    @Test
    void shouldReturnTheSameOperationForARepeatedIdempotencyKey() {
        // §13: the point of the key is that a retry after a network failure does not duplicate
        // expensive extraction work.
        ExtractionOperation first = client.submitExtraction(submitRequest("job-repeat"));
        ExtractionOperation second = client.submitExtraction(submitRequest("job-repeat"));

        assertThat(second.getOperationId()).isEqualTo(first.getOperationId());
        assertThat(plane.submitCount()).isEqualTo(2);
        assertThat(plane.operationCount()).isEqualTo(1);
    }

    @Test
    void shouldPollStatusByOperationId() {
        ExtractionOperation submitted = client.submitExtraction(submitRequest("job-2"));

        GetOperationsResponse response = client.getOperations(GetOperationsRequest.newBuilder()
                .setTenantId(TENANT)
                .addOperationIds(submitted.getOperationId())
                .build());

        assertThat(response.getOperationsList()).hasSize(1);
        assertThat(response.getOperations(0).getOperationId()).isEqualTo(submitted.getOperationId());
    }

    @Test
    void shouldReportUnknownOperationIdsSeparately() {
        // A caller must be able to tell "purged or never existed" from "still running".
        GetOperationsResponse response = client.getOperations(GetOperationsRequest.newBuilder()
                .setTenantId(TENANT)
                .addOperationIds("no-such-operation")
                .build());

        assertThat(response.getOperationsList()).isEmpty();
        assertThat(response.getNotFoundOperationIdsList()).containsExactly("no-such-operation");
    }

    @Test
    void shouldReturnPayloadFlattenedTextAndFeatureStatesInTheResult() {
        ExtractionOperation submitted = client.submitExtraction(submitRequest("job-3"));
        plane.complete(submitted.getOperationId());

        ExtractionResult result = client.getResult(GetResultRequest.newBuilder()
                .setTenantId(TENANT)
                .setOperationId(submitted.getOperationId())
                .build());

        assertThat(result.getStatus()).isEqualTo(ExtractionStatus.STATUS_COMPLETED);
        assertThat(result.getFlattenedText()).isNotBlank();
        assertThat(result.getPayload().getPayloadDescriptor().getSchemaId())
                .isEqualTo("synanton.extraction.document");
        assertThat(result.getFeatureStatesMap())
                .containsEntry("text", FeatureState.FEATURE_APPLIED)
                .containsEntry("ocr", FeatureState.FEATURE_NOT_APPLICABLE);
    }

    @Test
    void shouldBindTheResultToTheSourceDigest() {
        // §25: a consumer must be able to tell whether a result belongs to the bytes it expects.
        ExtractionOperation submitted = client.submitExtraction(submitRequest("job-4"));
        plane.complete(submitted.getOperationId());

        ExtractionResult result = client.getResult(GetResultRequest.newBuilder()
                .setTenantId(TENANT)
                .setOperationId(submitted.getOperationId())
                .build());

        assertThat(result.getProvenance().getSourceSha256()).isEqualTo(SHA);
        assertThat(result.getProvenance().getContentRefId()).isEqualTo("content-pdf-001");
    }

    @Test
    void shouldEchoBusinessTagsWithoutInterpretingThem() {
        // §67.11: business metadata is carried, not interpreted.
        ExtractionOperation submitted = client.submitExtraction(submitRequest("job-5"));
        plane.complete(submitted.getOperationId());

        ExtractionResult result = client.getResult(GetResultRequest.newBuilder()
                .setTenantId(TENANT)
                .setOperationId(submitted.getOperationId())
                .build());

        assertThat(result.getBusinessTagsMap()).containsEntry("department", "legal");
    }

    @Test
    void shouldWalkCompletedOperationsByCursor() {
        // §17.2: each terminal operation appears exactly once across the cursor sequence.
        String firstId = client.submitExtraction(submitRequest("job-c1")).getOperationId();
        String secondId = client.submitExtraction(submitRequest("job-c2")).getOperationId();
        plane.complete(firstId);
        plane.complete(secondId);

        ListCompletedOperationsResponse page = client.listCompletedOperations(
                ListCompletedOperationsRequest.newBuilder()
                        .setTenantId(TENANT)
                        .setPageSize(1)
                        .build());

        assertThat(page.getOperationsList()).hasSize(1);
        assertThat(page.getNextCursor()).isNotBlank();

        ListCompletedOperationsResponse next = client.listCompletedOperations(
                ListCompletedOperationsRequest.newBuilder()
                        .setTenantId(TENANT)
                        .setPageSize(1)
                        .setCursor(page.getNextCursor())
                        .build());

        assertThat(next.getOperationsList()).hasSize(1);
        assertThat(next.getOperations(0).getOperationId())
                .isNotEqualTo(page.getOperations(0).getOperationId());
    }

    @Test
    void shouldNotTransportSourceBytesThroughTheContract() {
        // The plane reads from object storage; the request carries only a reference.
        SubmitExtractionRequest request = submitRequest("job-6");

        assertThat(request.getItem().getSource().getKey()).isNotBlank();
        assertThat(request.getItem().getAllFields().keySet())
                .describedAs("no field on the request item may carry content bytes")
                .noneSatisfy(field -> assertThat(field.getName()).isEqualTo("content"));
    }

    /**
     * Minimal in-process stand-in for the extraction plane. It implements only enough of the
     * contract for the client assertions above, and deliberately keeps the idempotency behaviour
     * the contract requires.
     */
    private static final class MockExtractionPlane
            extends ExtractionServiceGrpc.ExtractionServiceImplBase {

        private final Map<String, ExtractionOperation> operationsByKey = new HashMap<>();
        private final Map<String, ExtractionOperation> operationsById = new HashMap<>();
        private final Map<String, SubmitExtractionRequest> requestsById = new HashMap<>();
        private final java.util.List<String> completionOrder = new java.util.ArrayList<>();
        private int submitCount;

        int submitCount() {
            return submitCount;
        }

        int operationCount() {
            return operationsById.size();
        }

        void complete(String operationId) {
            ExtractionOperation existing = operationsById.get(operationId);
            ExtractionOperation completed = existing.toBuilder()
                    .setStatus(ExtractionStatus.STATUS_COMPLETED)
                    .setProgress(1.0)
                    .build();
            operationsById.put(operationId, completed);
            if (!completionOrder.contains(operationId)) {
                completionOrder.add(operationId);
            }
        }

        @Override
        public void submitExtraction(
                SubmitExtractionRequest request, StreamObserver<ExtractionOperation> observer) {
            submitCount++;
            ExtractionOperation existing = operationsByKey.get(request.getIdempotencyKey());
            if (existing != null) {
                observer.onNext(existing);
                observer.onCompleted();
                return;
            }

            ExtractionOperation operation = ExtractionOperation.newBuilder()
                    .setOperationId(UUID.randomUUID().toString())
                    .setTenantId(request.getTenantId())
                    .setStatus(ExtractionStatus.STATUS_ACCEPTED)
                    .setProgress(0.0)
                    .build();

            operationsByKey.put(request.getIdempotencyKey(), operation);
            operationsById.put(operation.getOperationId(), operation);
            requestsById.put(operation.getOperationId(), request);

            observer.onNext(operation);
            observer.onCompleted();
        }

        @Override
        public void getOperations(
                GetOperationsRequest request, StreamObserver<GetOperationsResponse> observer) {
            GetOperationsResponse.Builder response = GetOperationsResponse.newBuilder();
            for (String id : request.getOperationIdsList()) {
                ExtractionOperation operation = operationsById.get(id);
                if (operation == null) {
                    response.addNotFoundOperationIds(id);
                } else {
                    response.addOperations(operation);
                }
            }
            observer.onNext(response.build());
            observer.onCompleted();
        }

        @Override
        public void listCompletedOperations(
                ListCompletedOperationsRequest request,
                StreamObserver<ListCompletedOperationsResponse> observer) {
            int offset = request.getCursor().isBlank() ? 0 : Integer.parseInt(request.getCursor());
            int pageSize = request.getPageSize();

            ListCompletedOperationsResponse.Builder response = ListCompletedOperationsResponse.newBuilder();
            int index = offset;
            while (index < completionOrder.size() && response.getOperationsCount() < pageSize) {
                response.addOperations(operationsById.get(completionOrder.get(index)));
                index++;
            }
            if (index < completionOrder.size()) {
                response.setNextCursor(String.valueOf(index));
            }
            observer.onNext(response.build());
            observer.onCompleted();
        }

        @Override
        public void getResult(GetResultRequest request, StreamObserver<ExtractionResult> observer) {
            SubmitExtractionRequest original = requestsById.get(request.getOperationId());
            ExtractionOperation operation = operationsById.get(request.getOperationId());
            String flattened = "The system processes enterprise content.";

            StructuredPayload payload = StructuredPayload.newBuilder()
                    .setPayloadDescriptor(PayloadDescriptor.newBuilder()
                            .setSchemaId("synanton.extraction.document")
                            .setSchemaVersion("1.0")
                            .setProcessorId("pdf-adapter")
                            .setProcessorVersion("mock-1")
                            .setFormat(SerializationFormat.SERIALIZATION_JSON)
                            .setPayloadDigest("c".repeat(64)))
                    .setInlineContent(ByteString.copyFromUtf8("{\"elements\":[]}"))
                    .build();

            ExtractionResult result = ExtractionResult.newBuilder()
                    .setOperationId(request.getOperationId())
                    .setContentRefId(original.getItem().getContentRefId())
                    .setStatus(operation.getStatus())
                    .setPayload(payload)
                    .setFlattenedText(flattened)
                    .putFeatureStates("text", FeatureState.FEATURE_APPLIED)
                    .putFeatureStates("ocr", FeatureState.FEATURE_NOT_APPLICABLE)
                    .setProvenance(synanton.extraction.v1.ResultProvenance.newBuilder()
                            .setContentRefId(original.getItem().getContentRefId())
                            .setSourceSha256(original.getItem().getSource().getSha256())
                            .setSource(original.getItem().getSource()))
                    .putAllBusinessTags(original.getItem().getBusinessTagsMap())
                    .build();

            observer.onNext(result);
            observer.onCompleted();
        }
    }
}

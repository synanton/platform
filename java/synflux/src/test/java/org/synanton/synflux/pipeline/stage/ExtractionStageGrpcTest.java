package org.synanton.synflux.pipeline.stage;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.synanton.extraction.client.ExtractionClientMetrics;
import org.synanton.extraction.client.ExtractionClientProperties;
import org.synanton.extraction.client.ExtractionFallbackPolicy;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.client.LocalTikaFallbackExtractor;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ObjectStorePort;
import org.synanton.extraction.v1.DocumentElement;
import org.synanton.extraction.v1.DocumentElementType;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.ExtractionResult;
import org.synanton.extraction.v1.ExtractionServiceGrpc;
import org.synanton.extraction.v1.ExtractionStatus;
import org.synanton.extraction.v1.GetOperationsRequest;
import org.synanton.extraction.v1.GetOperationsResponse;
import org.synanton.extraction.v1.GetResultRequest;
import org.synanton.extraction.v1.StructuredPayload;
import org.synanton.extraction.v1.SubmitExtractionRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Covers {@link ExtractionStage}'s real success and terminal-failure paths against a mock
 * gRPC server (in-process, not a real content_extractor gateway) — the plane-enabled path
 * that {@link ExtractionStageTest} does not exercise (it only covers the client-disabled
 * fallback). For a real end-to-end proof against a live content_extractor gateway, see
 * {@code ExtractionStageRealGatewayTest} (manual-run only; needs Docker + both repos built).
 */
class ExtractionStageGrpcTest {

    private static final String TENANT = "demo";
    private static final String HOT_BUCKET = "synanton-hot";

    private Server server;
    private String endpoint;
    private MockExtractionService mockService;

    @BeforeEach
    void setUp() throws IOException {
        mockService = new MockExtractionService();
        server = ServerBuilder.forPort(0).addService(mockService).build().start();
        endpoint = "localhost:" + server.getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void shouldReturnStructuredPayloadWhenPlaneCompletesSuccessfully() {
        DocumentPayload payload = DocumentPayload.newBuilder()
                .setMediaType("application/pdf")
                .setFlattenedText("Quarterly Report\nRevenue grew in Q1.")
                .addElements(DocumentElement.newBuilder()
                        .setId("p1-e1")
                        .setType(DocumentElementType.ELEMENT_HEADING)
                        .setText("Quarterly Report")
                        .build())
                .addElements(DocumentElement.newBuilder()
                        .setId("p1-e2")
                        .setType(DocumentElementType.ELEMENT_PARAGRAPH)
                        .setText("Revenue grew in Q1.")
                        .build())
                .build();
        mockService.syncResult = ExtractionResult.newBuilder()
                .setOperationId("op-1")
                .setStatus(ExtractionStatus.STATUS_COMPLETED)
                .setFlattenedText(payload.getFlattenedText())
                .setPayload(StructuredPayload.newBuilder()
                        .setInlineContent(payload.toByteString())
                        .build())
                .build();

        ObjectStorePort objectStore = mock(ObjectStorePort.class);
        ExtractionStage stage = buildStage(objectStore, "sync", ExtractionFallbackPolicy.FALLBACK_LOCAL_TIKA);

        UUID contentRefId = UUID.randomUUID();
        byte[] bytes = "irrelevant, plane response is mocked".getBytes();
        AcquiredDocument doc = new AcquiredDocument(
                new ContentRef("file", "file:///tmp/report.pdf", "application/pdf", bytes.length, Instant.now()),
                bytes, "sha", "application/pdf", "file:///tmp/report.pdf", contentRefId);

        ParsedDocument parsed = stage.apply(doc, new StageContext(TENANT, "job-1", null));

        assertThat(parsed.documentPayload()).isNotNull();
        assertThat(parsed.documentPayload().getElementsList())
                .extracting(DocumentElement::getType)
                .containsExactly(DocumentElementType.ELEMENT_HEADING, DocumentElementType.ELEMENT_PARAGRAPH);
        assertThat(parsed.text()).isEqualTo("Quarterly Report\nRevenue grew in Q1.");

        ArgumentCaptor<Long> sizeCaptor = ArgumentCaptor.forClass(Long.class);
        verify(objectStore).putObject(
                eq(HOT_BUCKET), eq(TENANT + "/" + contentRefId), any(InputStream.class),
                sizeCaptor.capture(), eq("application/pdf"));
        assertThat(sizeCaptor.getValue()).isEqualTo((long) bytes.length);
    }

    @Test
    void shouldFallBackWhenPlaneReturnsTerminalFailureWithoutException() {
        mockService.syncResult = ExtractionResult.newBuilder()
                .setOperationId("op-2")
                .setStatus(ExtractionStatus.STATUS_FAILED)
                .build();

        ObjectStorePort objectStore = mock(ObjectStorePort.class);
        ExtractionStage stage = buildStage(objectStore, "sync", ExtractionFallbackPolicy.FALLBACK_LOCAL_TIKA);

        byte[] bytes = "hello from tika fallback".getBytes();
        AcquiredDocument doc = new AcquiredDocument(
                new ContentRef("file", "file:///tmp/note.txt", "text/plain", bytes.length, Instant.now()),
                bytes, "sha", "text/plain", "file:///tmp/note.txt", UUID.randomUUID());

        ParsedDocument parsed = stage.apply(doc, new StageContext(TENANT, "job-2", null));

        assertThat(parsed.documentPayload()).isNull();
        assertThat(parsed.text()).contains("hello from tika fallback");
    }

    private ExtractionStage buildStage(ObjectStorePort objectStore, String mode, ExtractionFallbackPolicy policy) {
        ExtractionClientProperties props = new ExtractionClientProperties(
                true, endpoint, mode, 5, 1, "local-tika", "NORMAL");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExtractionPlaneClient client = new ExtractionPlaneClient(props, new ExtractionClientMetrics(registry));
        return new ExtractionStage(
                client, new LocalTikaFallbackExtractor(), policy,
                new ExtractionClientMetrics(registry), objectStore, HOT_BUCKET);
    }

    private static final class MockExtractionService extends ExtractionServiceGrpc.ExtractionServiceImplBase {
        ExtractionResult syncResult;

        @Override
        public void extractSync(SubmitExtractionRequest request, StreamObserver<ExtractionResult> responseObserver) {
            responseObserver.onNext(syncResult);
            responseObserver.onCompleted();
        }

        @Override
        public void getOperations(GetOperationsRequest request,
                                   StreamObserver<GetOperationsResponse> responseObserver) {
            responseObserver.onNext(GetOperationsResponse.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void getResult(GetResultRequest request, StreamObserver<ExtractionResult> responseObserver) {
            responseObserver.onNext(syncResult);
            responseObserver.onCompleted();
        }
    }
}

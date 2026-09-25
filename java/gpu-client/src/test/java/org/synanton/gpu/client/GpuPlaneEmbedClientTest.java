package org.synanton.gpu.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.synanton.gpu.v1.*;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.LlmClient;
import org.synanton.llm.TenantAwareLlmClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GpuPlaneEmbedClient against a real gRPC server: payload, fail-closed semantics, retries, mTLS. */
class GpuPlaneEmbedClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final EmbedRequest REQ = new EmbedRequest("synanton-free-embedding", List.of("alpha", "beta"));

    private Server server;
    private ManagedChannel channel;
    private final List<ExecutionRequest> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void stop() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    /** Fake gateway: the handler gets (attempt number starting at 1, request) and answers the observer. */
    private GpuPlaneEmbedClient client(BiConsumer<Integer, StreamObserver<ExecutionResponse>> handler) throws Exception {
        server = NettyServerBuilder.forPort(0).addService(service(handler)).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        return new GpuPlaneEmbedClient(props(server.getPort()), MAPPER, channel);
    }

    private GPUExecutionServiceGrpc.GPUExecutionServiceImplBase service(
            BiConsumer<Integer, StreamObserver<ExecutionResponse>> handler) {
        return new GPUExecutionServiceGrpc.GPUExecutionServiceImplBase() {
            @Override
            public void execute(ExecutionRequest request, StreamObserver<ExecutionResponse> observer) {
                seen.add(request);
                handler.accept(calls.incrementAndGet(), observer);
            }
        };
    }

    private static GpuPlaneClientProperties props(int port) {
        GpuPlaneClientProperties p = new GpuPlaneClientProperties();
        p.setEndpoint("localhost:" + port);
        p.setTimeoutMs(10_000);
        p.getTls().setEnabled(false);
        p.getRetry().setMaxAttempts(3);
        p.getRetry().setBackoffBaseMs(1);
        p.getRetry().setRateLimitedBackoffMs(1);
        return p;
    }

    /** OpenAI-compatible result with data[] deliberately out of index order. */
    private static void success(StreamObserver<ExecutionResponse> o, int vectors) {
        StringBuilder data = new StringBuilder();
        for (int i = vectors - 1; i >= 0; i--) {
            data.append(data.isEmpty() ? "" : ",")
                    .append("{\"object\":\"embedding\",\"index\":").append(i)
                    .append(",\"embedding\":[").append(i + 1).append(".0,0.5]}");
        }
        String body = "{\"object\":\"list\",\"model\":\"synanton-free-embedding\",\"data\":[" + data
                + "],\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}";
        o.onNext(ExecutionResponse.newBuilder().setState(ExecutionState.SUCCESS)
                .setResult(ByteString.copyFromUtf8(body)).build());
        o.onCompleted();
    }

    private static void denial(StreamObserver<ExecutionResponse> o, Status status, String code) {
        Metadata trailers = new Metadata();
        if (code != null) trailers.put(GpuErrorCodes.ERROR_CODE_KEY, code);
        o.onError(status.asRuntimeException(trailers));
    }

    private static void failed(StreamObserver<ExecutionResponse> o, ErrorReason reason, String code, boolean retryable) {
        o.onNext(ExecutionResponse.newBuilder().setState(ExecutionState.FAILED).setUpstreamRequestId("up-1")
                .setError(ErrorInfo.newBuilder().setReason(reason).setCode(code).setRetryable(retryable)).build());
        o.onCompleted();
    }

    // ─── success path ────────────────────────────────────────────────────────

    @Test
    void successSendsLogicalModelAndTenantAndOrdersVectorsByIndex() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> success(o, 2));

        EmbedResponse r = c.embed(REQ, "rb-fixed-g");

        assertThat(r.embeddings()).hasSize(2);
        assertThat(r.embeddings().get(0)[0]).isEqualTo(1.0f);
        assertThat(r.embeddings().get(1)[0]).isEqualTo(2.0f);
        assertThat(r.inputTokens()).isEqualTo(7);
        assertThat(r.inputChars()).isEqualTo("alpha".length() + "beta".length());

        ExecutionRequest sent = seen.get(0);
        assertThat(sent.getOperation()).isEqualTo(Operation.EMBED);
        assertThat(sent.getTenantId()).isEqualTo("rb-fixed-g");
        assertThat(sent.getModel()).isEqualTo("synanton-free-embedding");
        var payload = MAPPER.readTree(sent.getPayload().toByteArray());
        assertThat(payload.get("model").asText()).isEqualTo("synanton-free-embedding");
        assertThat(payload.get("input")).hasSize(2);
    }

    @Test
    void tenantHelperDispatchesToTenantAwareClientsOnly() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> success(o, 2));
        TenantAwareLlmClient.embed(c, REQ, "rb-semantic-g");
        assertThat(seen.get(0).getTenantId()).isEqualTo("rb-semantic-g");

        LlmClient plain = new LlmClient() {
            @Override public CompletionResponse complete(CompletionRequest request) { return null; }
            @Override public EmbedResponse embed(EmbedRequest request) { return new EmbedResponse(List.of(new float[]{9f})); }
        };
        assertThat(TenantAwareLlmClient.embed(plain, REQ, "ignored").embeddings().get(0)[0]).isEqualTo(9f);
    }

    // ─── fail closed ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"circuit_open", "routing_disabled", "budget_exceeded", "tenant_not_allowed",
            "sensitive_model_external_blocked", "no_local_fallback", "provider_unavailable"})
    void policyDenialsFailImmediatelyWithTheCanonicalCode(String code) throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> denial(o, Status.FAILED_PRECONDITION, code));

        assertThatThrownBy(() -> c.embed(REQ, "rb-fixed-g"))
                .isInstanceOf(GpuPlaneException.class)
                .satisfies(e -> assertThat(((GpuPlaneException) e).code()).isEqualTo(code));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void failedExecutionThrowsItsErrorInfoCodeWithoutRetry() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> failed(o, ErrorReason.EXECUTION_FAILED, "upstream_provider_error", false));

        assertThatThrownBy(() -> c.embed(REQ, "rb-fixed-g"))
                .isInstanceOf(GpuPlaneException.class)
                .satisfies(e -> assertThat(((GpuPlaneException) e).code()).isEqualTo("upstream_provider_error"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void wrongVectorCountIsAnInvalidResultNotPartialData() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> success(o, 1));

        assertThatThrownBy(() -> c.embed(REQ, "rb-fixed-g"))
                .isInstanceOf(GpuPlaneException.class)
                .satisfies(e -> assertThat(((GpuPlaneException) e).code()).isEqualTo("invalid_result"));
    }

    @Test
    void missingTenantFailsBeforeAnyCall() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> success(o, 2));

        assertThatThrownBy(() -> c.embed(REQ))
                .isInstanceOf(GpuPlaneException.class)
                .satisfies(e -> assertThat(((GpuPlaneException) e).code()).isEqualTo("tenant_required"));
        assertThatThrownBy(() -> c.embed(REQ, " "))
                .isInstanceOf(GpuPlaneException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void completeIsNotSupported() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> success(o, 2));
        assertThatThrownBy(() -> c.complete(null))
                .isInstanceOf(GpuPlaneException.class)
                .satisfies(e -> assertThat(((GpuPlaneException) e).code()).isEqualTo("capability_not_supported"));
    }

    // ─── transient outcomes are retried, then still fail closed ──────────────

    @Test
    void transportUnavailableIsRetriedThenFailsClosed() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> denial(o, Status.UNAVAILABLE, null));

        assertThatThrownBy(() -> c.embed(REQ, "rb-fixed-g"))
                .isInstanceOf(GpuPlaneException.class)
                .satisfies(e -> assertThat(((GpuPlaneException) e).code()).isEqualTo("status_unavailable"));
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void capacityDenialIsRetried() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> {
            if (n == 1) denial(o, Status.RESOURCE_EXHAUSTED, "concurrency_limit_reached");
            else success(o, 2);
        });

        assertThat(c.embed(REQ, "rb-fixed-g").embeddings()).hasSize(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void retryableModelNotReadyIsRetried() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> {
            if (n == 1) failed(o, ErrorReason.MODEL_NOT_READY, "model_not_ready", true);
            else success(o, 2);
        });

        assertThat(c.embed(REQ, "rb-fixed-g").embeddings()).hasSize(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void providerRateLimitIsRetriedAfterAPause() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> {
            if (n == 1) failed(o, ErrorReason.EXECUTION_FAILED, GpuErrorCodes.PROVIDER_RATE_LIMITED, true);
            else success(o, 2);
        });

        assertThat(c.embed(REQ, "rb-fixed-g").embeddings()).hasSize(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void nonRetryableRateLimitFailsClosed() throws Exception {
        GpuPlaneEmbedClient c = client((n, o) -> failed(o, ErrorReason.EXECUTION_FAILED, GpuErrorCodes.PROVIDER_RATE_LIMITED, false));
        assertThatThrownBy(() -> c.embed(REQ, "rb-fixed-g")).isInstanceOf(GpuPlaneException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void pacerSpacesCallsToTheConfiguredRate() {
        RequestPacer pacer = new RequestPacer(600); // one slot per 100 ms
        long start = System.nanoTime();
        for (int i = 0; i < 4; i++) pacer.acquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isBetween(280L, 2_000L); // 3 intervals after the first free slot
        assertThat(new RequestPacer(0).acquire()).isZero();
    }

    // ─── mTLS ────────────────────────────────────────────────────────────────

    @Test
    void tlsEnabledWithoutFilesFailsAtConstruction() {
        GpuPlaneClientProperties p = props(1);
        p.getTls().setEnabled(true);
        p.getTls().setCaPath("/nonexistent/ca.crt");

        assertThatThrownBy(() -> new GpuPlaneEmbedClient(p, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpu-plane.tls.ca-path");
    }

    @Test
    void mtlsChannelPresentsTheClientCertificate(@TempDir Path pki) throws Exception {
        Assumptions.assumeTrue(new File("/usr/bin/openssl").canExecute() || new File("/usr/local/bin/openssl").canExecute(),
                "openssl required");
        selfSignedPki(pki);
        var sslServer = GrpcSslContexts.forServer(pki.resolve("server.crt").toFile(), pki.resolve("server.key").toFile())
                .trustManager(pki.resolve("ca.crt").toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build();
        server = NettyServerBuilder.forPort(0).sslContext(sslServer)
                .addService(service((n, o) -> success(o, 2))).build().start();

        GpuPlaneClientProperties p = props(server.getPort());
        p.getTls().setEnabled(true);
        p.getTls().setCaPath(pki.resolve("ca.crt").toString());
        p.getTls().setCertPath(pki.resolve("synanton-platform.crt").toString());
        p.getTls().setKeyPath(pki.resolve("synanton-platform.key").toString());

        try (GpuPlaneEmbedClient c = new GpuPlaneEmbedClient(p, MAPPER)) {
            assertThat(c.embed(REQ, "rb-fixed-g").embeddings()).hasSize(2);
        }
    }

    private static void openssl(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("openssl"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("openssl " + String.join(" ", args) + ": " + out);
        }
    }

    /** Self-signed CA + server cert (SAN localhost) + client cert — same shape as gpu-runtime gen-certs.sh. */
    private static void selfSignedPki(Path dir) throws Exception {
        openssl(dir, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=test-ca",
                "-keyout", "ca.key", "-out", "ca.crt");
        for (String[] c : new String[][]{{"server", "/CN=gpu-gateway", "subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n"},
                {"synanton-platform", "/CN=synanton-platform", "extendedKeyUsage=clientAuth\n"}}) {
            openssl(dir, "req", "-newkey", "rsa:2048", "-nodes", "-subj", c[1], "-keyout", c[0] + ".key", "-out", c[0] + ".csr");
            Files.writeString(dir.resolve(c[0] + ".ext"), c[2]);
            openssl(dir, "x509", "-req", "-in", c[0] + ".csr", "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial",
                    "-days", "1", "-extfile", c[0] + ".ext", "-out", c[0] + ".crt");
        }
    }
}

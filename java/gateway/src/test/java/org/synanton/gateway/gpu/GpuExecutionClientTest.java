package org.synanton.gateway.gpu;

import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.gpu.v1.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GpuExecutionClient against a real gRPC server: mTLS channel (self-signed CA, client
 * certificate), ExecuteStream consumption, canonical error-code helpers.
 */
class GpuExecutionClientTest {

    private Server server;

    /** Fake GPU gateway: GetModels echoes the caller's presence; ExecuteStream streams 2 chunks + terminal. */
    private static final GPUExecutionServiceGrpc.GPUExecutionServiceImplBase FAKE =
            new GPUExecutionServiceGrpc.GPUExecutionServiceImplBase() {
                @Override
                public void getModels(GetModelsRequest request, StreamObserver<GetModelsResponse> observer) {
                    observer.onNext(GetModelsResponse.newBuilder()
                            .addModels(ModelInfo.newBuilder().setModelId("synanton-free-chat")).build());
                    observer.onCompleted();
                }

                @Override
                public void executeStream(ExecutionRequest request, StreamObserver<ExecutionChunk> observer) {
                    for (String part : List.of("{\"n\":1}", "{\"n\":2}")) {
                        observer.onNext(ExecutionChunk.newBuilder().setRequestId(request.getRequestId())
                                .setData(ByteString.copyFromUtf8(part)).build());
                    }
                    observer.onNext(ExecutionChunk.newBuilder().setTerminal(ExecutionResponse.newBuilder()
                            .setState(ExecutionState.SUCCESS).setUpstreamRequestId("upstream-1")).build());
                    observer.onCompleted();
                }
            };

    @AfterEach
    void stop() {
        if (server != null) {
            server.shutdownNow();
        }
    }

    private static GpuExecutionClientProperties props(int port) {
        GpuExecutionClientProperties p = new GpuExecutionClientProperties();
        p.setEnabled(true);
        p.setEndpoint("localhost:" + port);
        p.setTimeoutMs(10_000);
        return p;
    }

    @Test
    void executeStreamYieldsDataChunksThenTerminal() throws Exception {
        server = NettyServerBuilder.forPort(0).addService(FAKE).build().start();
        GpuExecutionClient client = new GpuExecutionClient(props(server.getPort()));

        List<ExecutionChunk> chunks = new ArrayList<>();
        client.executeStream(ExecutionRequest.newBuilder().setRequestId("r1").build()).forEachRemaining(chunks::add);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getData().toStringUtf8()).isEqualTo("{\"n\":1}");
        assertThat(chunks.get(2).getTerminal().getUpstreamRequestId()).isEqualTo("upstream-1");
    }

    @Test
    void canonicalCodeComesFromTheTrailerOrErrorInfo() {
        io.grpc.Metadata trailers = new io.grpc.Metadata();
        trailers.put(GpuExecutionClient.ERROR_CODE_KEY, "budget_exceeded");
        StatusRuntimeException denial = Status.RESOURCE_EXHAUSTED.asRuntimeException(trailers);

        assertThat(GpuExecutionClient.canonicalCode(denial)).isEqualTo("budget_exceeded");
        assertThat(GpuExecutionClient.isRetryableDenial(denial)).isFalse();
        assertThat(GpuExecutionClient.canonicalCode(Status.UNAVAILABLE.asRuntimeException())).isEqualTo("status_unavailable");
        assertThat(GpuExecutionClient.canonicalCode(ExecutionResponse.newBuilder().setState(ExecutionState.FAILED)
                .setError(ErrorInfo.newBuilder().setCode("upstream_provider_timeout")).build()))
                .isEqualTo("upstream_provider_timeout");
    }

    @Test
    void tlsEnabledWithoutFilesFailsClosed() {
        GpuExecutionClientProperties p = props(1);
        p.getTls().setEnabled(true);
        p.getTls().setCaPath("/nonexistent/ca.crt");

        assertThatThrownBy(() -> new GpuExecutionClient(p).buildChannel())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ca-path");
    }

    // ─── mTLS against a server that REQUIRES a client certificate ────────────

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

    @Test
    void mtlsChannelPresentsTheClientCertificate(@TempDir Path pki) throws Exception {
        Assumptions.assumeTrue(new File("/usr/bin/openssl").canExecute() || new File("/usr/local/bin/openssl").canExecute(),
                "openssl required");
        selfSignedPki(pki);
        server = NettyServerBuilder.forPort(0)
                .sslContext(GrpcSslContexts.configure(SslContextBuilder.forServer(
                                        pki.resolve("server.crt").toFile(), pki.resolve("server.key").toFile())
                                .trustManager(pki.resolve("ca.crt").toFile())
                                .clientAuth(ClientAuth.REQUIRE), SslProvider.JDK).build())
                .addService(FAKE).build().start();

        GpuExecutionClientProperties p = props(server.getPort());
        p.getTls().setEnabled(true);
        p.getTls().setCaPath(pki.resolve("ca.crt").toString());
        p.getTls().setCertPath(pki.resolve("synanton-platform.crt").toString());
        p.getTls().setKeyPath(pki.resolve("synanton-platform.key").toString());

        GetModelsResponse models = new GpuExecutionClient(p).getModels(GetModelsRequest.getDefaultInstance());
        assertThat(models.getModelsList()).extracting(ModelInfo::getModelId).containsExactly("synanton-free-chat");

        // the same server refuses a plaintext client (no certificate)
        GpuExecutionClientProperties plaintext = props(server.getPort());
        assertThatThrownBy(() -> new GpuExecutionClient(plaintext).getModels(GetModelsRequest.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class);
    }
}

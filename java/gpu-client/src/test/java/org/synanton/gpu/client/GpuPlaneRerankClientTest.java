package org.synanton.gpu.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.synanton.gpu.v1.*;
import org.synanton.llm.RerankRequest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GpuPlaneRerankClient against a real gRPC server: payload + template, ordering, fail-closed parsing. */
class GpuPlaneRerankClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RerankRequest REQ = new RerankRequest("synanton-qwen3-reranker-0.6b", "default ip?",
            List.of("unrelated", "the address is 192.168.10.12", "also unrelated"), 0);

    private Server server;
    private ManagedChannel channel;
    private final List<ExecutionRequest> seen = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    private GpuPlaneRerankClient client(RerankPromptFormat format, Function<ExecutionRequest, Object> answer) throws Exception {
        server = NettyServerBuilder.forPort(0).addService(new GPUExecutionServiceGrpc.GPUExecutionServiceImplBase() {
            @Override
            public void execute(ExecutionRequest request, StreamObserver<ExecutionResponse> o) {
                seen.add(request);
                Object a = answer.apply(request);
                if (a instanceof Status st) {
                    Metadata md = new Metadata();
                    md.put(GpuErrorCodes.ERROR_CODE_KEY, "circuit_open");
                    o.onError(st.asRuntimeException(md));
                    return;
                }
                o.onNext(ExecutionResponse.newBuilder().setState(ExecutionState.SUCCESS)
                        .setResult(ByteString.copyFromUtf8((String) a)).build());
                o.onCompleted();
            }
        }).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        GpuPlaneClientProperties p = new GpuPlaneClientProperties();
        p.getTls().setEnabled(false);
        p.getRetry().setBackoffBaseMs(1);
        return new GpuPlaneRerankClient(p, MAPPER, format, null, channel);
    }

    private static final String GOOD = "{\"results\":[{\"index\":1,\"relevance_score\":0.99},{\"index\":0,\"relevance_score\":0.01},"
            + "{\"index\":2,\"relevance_score\":0.02}],\"usage\":{\"prompt_tokens\":42}}";

    @Test
    void sortsByScoreReturnsOriginalPassagesAndAppliesTheQwen3Template() throws Exception {
        var c = client(RerankPromptFormat.QWEN3, r -> GOOD);
        var res = c.rerank(REQ, "rb-fixed-g5");

        assertThat(res.results()).extracting(x -> x.index()).containsExactly(1, 2, 0);
        assertThat(res.results().get(0).passage()).isEqualTo("the address is 192.168.10.12"); // not templated
        assertThat(res.inputTokens()).isEqualTo(42);

        ExecutionRequest sent = seen.get(0);
        assertThat(sent.getOperation()).isEqualTo(Operation.RERANK);
        assertThat(sent.getTenantId()).isEqualTo("rb-fixed-g5");
        JsonNode body = MAPPER.readTree(sent.getPayload().toByteArray());
        assertThat(body.get("query").asText()).startsWith("<|im_start|>system").contains("<Query>: default ip?");
        assertThat(body.get("documents").get(1).asText()).startsWith("<Document>: the address is").endsWith("</think>\n\n");
    }

    @Test
    void plainFormatSendsTextAsIs() throws Exception {
        var c = client(RerankPromptFormat.PLAIN, r -> GOOD);
        c.rerank(REQ, "t");
        JsonNode body = MAPPER.readTree(seen.get(0).getPayload().toByteArray());
        assertThat(body.get("query").asText()).isEqualTo("default ip?");
        assertThat(body.get("documents").get(0).asText()).isEqualTo("unrelated");
    }

    @Test
    void partialDuplicateOrOutOfRangeResultsFailClosed() throws Exception {
        for (String bad : List.of(
                "{\"results\":[{\"index\":1,\"relevance_score\":0.9}]}",                                   // partial
                "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.8},{\"index\":0,\"relevance_score\":0.1}]}",
                "{\"results\":[{\"index\":7,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.8},{\"index\":0,\"relevance_score\":0.1}]}",
                "not json")) {
            var c = client(RerankPromptFormat.PLAIN, r -> bad);
            assertThatThrownBy(() -> c.rerank(REQ, "t"))
                    .isInstanceOf(GpuPlaneException.class)
                    .satisfies(e -> assertThat(((GpuPlaneException) e).code()).isEqualTo("invalid_result"));
            stop();
        }
    }

    @Test
    void policyDenialFailsClosedAndEmptyInputMakesNoCall() throws Exception {
        var c = client(RerankPromptFormat.PLAIN, r -> Status.UNAVAILABLE);
        assertThat(c.rerank(new RerankRequest("m", "q", List.of(), 0), "t").results()).isEmpty();
        assertThat(seen).isEmpty();
        assertThatThrownBy(() -> c.rerank(REQ, "t"))
                .isInstanceOf(GpuPlaneException.class)
                .satisfies(e -> assertThat(((GpuPlaneException) e).code()).isEqualTo("circuit_open"));
        assertThatThrownBy(() -> c.rerank(REQ, " ")).isInstanceOf(GpuPlaneException.class);
    }
}

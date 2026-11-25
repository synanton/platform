package org.synanton.extraction.engine.llm;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.LlmUsage;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.LlmClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmExtractionGatewayMetadataTest {

    @Test
    void preservesFinishReasonRequestAndExecutionOnSuccess() {
        LlmExtractionAdapter adapter = new LlmExtractionAdapter(
            client("stop", "req-1", "exec-1"), "model", 512, null, "extraction-v1");

        ExtractionResult result = adapter.extract(new TextSegment("chunk-1", "Thing", 0),
            new ExtractionContext("demo", "doc", Set.of("Concept"), "version"));

        assertThat(result.llmUsage().finishReason()).isEqualTo("stop");
        assertThat(result.provenance().finishReason()).isEqualTo("stop");
        assertThat(result.provenance().gatewayRequestId()).isEqualTo("req-1");
        assertThat(result.provenance().gatewayExecutionId()).isEqualTo("exec-1");
    }

    @Test
    void rejectsLengthBeforeAcceptingOutput() {
        LlmExtractionAdapter adapter = new LlmExtractionAdapter(
            client("length", "req-2", "exec-2"), "model", 512, null, "extraction-v1");

        assertThatThrownBy(() -> adapter.extract(new TextSegment("chunk-1", "Thing", 0),
            new ExtractionContext("demo", "doc", Set.of("Concept"), "version")))
            .isInstanceOf(LlmIncompleteOutputException.class)
            .hasMessageContaining("finish_reason=length");
    }

    @Test
    void rejectsNonSuccessFinish() {
        LlmExtractionAdapter adapter = new LlmExtractionAdapter(
            client("content_filter", "req-3", "exec-3"), "model", 512, null, "extraction-v1");

        assertThatThrownBy(() -> adapter.extract(new TextSegment("chunk-1", "Thing", 0),
            new ExtractionContext("demo", "doc", Set.of("Concept"), "version")))
            .isInstanceOf(LlmNonSuccessCompletionException.class)
            .hasMessageContaining("content_filter");
    }

    private static LlmClient client(String finish, String requestId, String executionId) {
        return new LlmClient() {
            @Override public CompletionResponse complete(CompletionRequest request) {
                return new CompletionResponse("{\"type\":\"entity\",\"surfaceForm\":\"Thing\","
                    + "\"proposedType\":\"Concept\",\"charStart\":0,\"charEnd\":5}",
                    10, 5, finish, requestId, executionId);
            }
            @Override public org.synanton.llm.EmbedResponse embed(org.synanton.llm.EmbedRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}

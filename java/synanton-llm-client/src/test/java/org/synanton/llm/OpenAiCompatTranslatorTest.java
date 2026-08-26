package org.synanton.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatTranslatorTest {

    private final OpenAiCompatTranslator translator = new OpenAiCompatTranslator();

    // ── buildCompletionPayload ──────────────────────────────────────────────

    @Test
    void buildCompletionPayload_includesSystemPromptWhenPresent() {
        CompletionRequest req = new CompletionRequest("gpt-4o", "Be concise.", "Hello", 0.7, 256);
        String payload = translator.buildCompletionPayload(req);

        assertThat(payload).contains("\"role\":\"system\"");
        assertThat(payload).contains("\"content\":\"Be concise.\"");
        assertThat(payload).contains("\"role\":\"user\"");
        assertThat(payload).contains("\"content\":\"Hello\"");
        assertThat(payload).contains("\"model\":\"gpt-4o\"");
        assertThat(payload).contains("\"temperature\":0.7");
        assertThat(payload).contains("\"max_tokens\":256");
    }

    @Test
    void buildCompletionPayload_omitsSystemPromptWhenNull() {
        CompletionRequest req = new CompletionRequest("gpt-4o", null, "Hello", 0.0, 128);
        String payload = translator.buildCompletionPayload(req);

        assertThat(payload).doesNotContain("\"role\":\"system\"");
        assertThat(payload).contains("\"role\":\"user\"");
    }

    @Test
    void buildCompletionPayload_omitsSystemPromptWhenBlank() {
        CompletionRequest req = new CompletionRequest("gpt-4o", "   ", "Hello", 0.0, 128);
        String payload = translator.buildCompletionPayload(req);

        assertThat(payload).doesNotContain("\"role\":\"system\"");
    }

    // ── parseCompletionResponse ─────────────────────────────────────────────

    @Test
    void parseCompletionResponse_parsesTokenCounts() {
        String json = """
                {
                  "choices": [{"message": {"role": "assistant", "content": "Hi there!"}}],
                  "usage": {"prompt_tokens": 12, "completion_tokens": 3}
                }
                """;
        CompletionResponse resp = translator.parseCompletionResponse(json);

        assertThat(resp.text()).isEqualTo("Hi there!");
        assertThat(resp.promptTokens()).isEqualTo(12);
        assertThat(resp.completionTokens()).isEqualTo(3);
    }

    @Test
    void parseCompletionResponse_defaultsTokenCountsWhenMissing() {
        String json = """
                {
                  "choices": [{"message": {"role": "assistant", "content": "ok"}}]
                }
                """;
        CompletionResponse resp = translator.parseCompletionResponse(json);

        assertThat(resp.promptTokens()).isZero();
        assertThat(resp.completionTokens()).isZero();
    }

    @Test
    void parseCompletionResponse_throwsOnMalformedJson() {
        assertThatThrownBy(() -> translator.parseCompletionResponse("not-json"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse completion response");
    }

    // ── buildEmbedPayload ───────────────────────────────────────────────────

    @Test
    void buildEmbedPayload_includesModelAndInputs() {
        EmbedRequest req = new EmbedRequest("text-embedding-3-small", List.of("foo", "bar"));
        String payload = translator.buildEmbedPayload(req);

        assertThat(payload).contains("\"model\":\"text-embedding-3-small\"");
        assertThat(payload).contains("\"input\":[\"foo\",\"bar\"]");
    }

    // ── parseEmbedResponse ──────────────────────────────────────────────────

    @Test
    void parseEmbedResponse_parsesFloatVectors() {
        String json = """
                {
                  "data": [
                    {"embedding": [0.1, 0.2, 0.3]},
                    {"embedding": [0.4, 0.5, 0.6]}
                  ]
                }
                """;
        EmbedResponse resp = translator.parseEmbedResponse(json, 8);

        assertThat(resp.embeddings()).hasSize(2);
        assertThat(resp.inputChars()).isEqualTo(8);
        assertThat(resp.inputTokens()).isZero();
        assertThat(resp.embeddings().get(0)).usingComparatorWithPrecision(0.001f)
                .containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(resp.embeddings().get(1)).usingComparatorWithPrecision(0.001f)
                .containsExactly(0.4f, 0.5f, 0.6f);
    }

    @Test
    void parseEmbedResponse_parsesEmptyData() {
        String json = "{\"data\": []}";
        EmbedResponse resp = translator.parseEmbedResponse(json, 8);

        assertThat(resp.embeddings()).isEmpty();
    }
}

package org.synanton.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicTranslatorTest {

    private final AnthropicDirectTranslator translator = new AnthropicDirectTranslator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildCompletionPayload_producesCorrectStructure() throws Exception {
        CompletionRequest req = new CompletionRequest("claude-3-5-haiku-20241022",
                "You are a helpful assistant.", "Summarise this document.", 0.0, 1024);

        String json = translator.buildCompletionPayload(req);
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("model").asText()).isEqualTo("claude-3-5-haiku-20241022");
        assertThat(root.get("max_tokens").asInt()).isEqualTo(1024);
        assertThat(root.get("system").asText()).isEqualTo("You are a helpful assistant.");
        assertThat(root.get("messages")).isNotNull();
        assertThat(root.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(root.get("messages").get(0).get("content").asText()).isEqualTo("Summarise this document.");
        // temperature is NOT in Anthropic payload
        assertThat(root.has("temperature")).isFalse();
    }

    @Test
    void buildCompletionPayload_noSystemPrompt_omitsSystemField() throws Exception {
        CompletionRequest req = new CompletionRequest("claude-3-5-haiku-20241022",
                null, "Hello", 0.0, 512);
        String json = translator.buildCompletionPayload(req);
        JsonNode root = mapper.readTree(json);
        assertThat(root.has("system")).isFalse();
    }

    @Test
    void parseCompletionResponse_extractsText() {
        String anthropicResponse = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "content": [{"type": "text", "text": "The answer is 42."}],
              "model": "claude-3-5-haiku-20241022",
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
            """;

        CompletionResponse response = translator.parseCompletionResponse(anthropicResponse);

        assertThat(response.text()).isEqualTo("The answer is 42.");
        assertThat(response.promptTokens()).isEqualTo(10);
        assertThat(response.completionTokens()).isEqualTo(5);
    }

    @Test
    void completionPath_isMessages() {
        assertThat(translator.completionPath()).isEqualTo("/messages");
    }

    @Test
    void buildEmbedPayload_throws() {
        EmbedRequest req = new EmbedRequest("model", java.util.List.of("text"));
        assertThatThrownBy(() -> translator.buildEmbedPayload(req))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lmClientFactory_anthropicDirect_returnsAnthropicClient() {
        LlmClient client = LlmClientFactory.create("anthropic-direct",
                "https://api.anthropic.com/v1", 3);
        assertThat(client).isInstanceOf(AnthropicHttpLlmClient.class);
    }

    @Test
    void lmClientFactory_openAiCompat_returnsHttpClient() {
        LlmClient client = LlmClientFactory.create("openai-compat",
                "http://localhost:8000/v1", 3);
        assertThat(client).isInstanceOf(HttpLlmClient.class);
    }

    @Test
    void lmClientFactory_bedrock_throws() {
        assertThatThrownBy(() -> LlmClientFactory.create("bedrock", "http://localhost", 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

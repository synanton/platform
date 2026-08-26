package org.synanton.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Translates {@link LlmClient} calls to the Anthropic Messages API wire format.
 *
 * <p>Retryable HTTP status codes: 529 (Overloaded), 5xx.
 * Non-retryable: 400 invalid_request_error.
 * Streaming is not supported in Phase 3.
 */
class AnthropicDirectTranslator implements LlmProviderTranslator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String buildCompletionPayload(CompletionRequest req) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", req.model());
        root.put("max_tokens", req.maxTokens());

        ArrayNode messages = MAPPER.createArrayNode();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            // Anthropic uses a top-level "system" field, not a system message in the array.
            root.put("system", req.systemPrompt());
        }
        messages.addObject().put("role", "user").put("content", req.userMessage());
        root.set("messages", messages);

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Anthropic payload", e);
        }
    }

    @Override
    public CompletionResponse parseCompletionResponse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            // Anthropic response: { content: [{ type: "text", text: "..." }], usage: { input_tokens, output_tokens } }
            String text = root.path("content").get(0).path("text").asText();
            int inputTokens = root.path("usage").path("input_tokens").asInt(0);
            int outputTokens = root.path("usage").path("output_tokens").asInt(0);
            return new CompletionResponse(text, inputTokens, outputTokens);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Anthropic completion response: " + json, e);
        }
    }

    @Override
    public String buildEmbedPayload(EmbedRequest req) {
        // Anthropic does not provide an embeddings endpoint; fall back to error.
        throw new UnsupportedOperationException(
                "Anthropic direct translator does not support embeddings. Use openai-compat with a separate embed server.");
    }

    @Override
    public EmbedResponse parseEmbedResponse(String json, int inputChars) {
        throw new UnsupportedOperationException("Anthropic direct translator does not support embeddings.");
    }

    @Override
    public String completionPath() {
        return "/messages";
    }

    @Override
    public String embeddingPath() {
        throw new UnsupportedOperationException("Anthropic direct translator does not support embeddings.");
    }
}

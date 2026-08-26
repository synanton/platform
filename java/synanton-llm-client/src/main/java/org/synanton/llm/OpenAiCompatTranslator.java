package org.synanton.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

class OpenAiCompatTranslator implements LlmProviderTranslator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String completionPath() { return "/chat/completions"; }

    @Override
    public String embeddingPath() { return "/embeddings"; }

    @Override
    public String buildCompletionPayload(CompletionRequest req) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", req.model());
        root.put("temperature", req.temperature());
        root.put("max_tokens", req.maxTokens());

        ArrayNode messages = MAPPER.createArrayNode();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", req.systemPrompt());
        }
        messages.addObject().put("role", "user").put("content", req.userMessage());
        root.set("messages", messages);

        try { return MAPPER.writeValueAsString(root); }
        catch (Exception e) { throw new RuntimeException("Failed to build payload", e); }
    }

    @Override
    public CompletionResponse parseCompletionResponse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode choice = root.path("choices").get(0);
            String text = choice.path("message").path("content").asText();
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            return new CompletionResponse(text, promptTokens, completionTokens);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse completion response: " + json, e);
        }
    }

    @Override
    public String buildEmbedPayload(EmbedRequest req) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", req.model());
        ArrayNode inputs = MAPPER.createArrayNode();
        req.inputs().forEach(inputs::add);
        root.set("input", inputs);
        try { return MAPPER.writeValueAsString(root); }
        catch (Exception e) { throw new RuntimeException("Failed to build embed payload", e); }
    }

    @Override
    public EmbedResponse parseEmbedResponse(String json, int inputChars) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode data = root.path("data");
            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embNode = item.path("embedding");
                float[] emb = new float[embNode.size()];
                for (int i = 0; i < embNode.size(); i++) {
                    emb[i] = (float) embNode.get(i).asDouble();
                }
                embeddings.add(emb);
            }
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int totalTokens = usage.path("total_tokens").asInt(promptTokens);
            int completionTokens = Math.max(0, totalTokens - promptTokens);
            return new EmbedResponse(embeddings, inputChars, 0, 0, promptTokens, completionTokens);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse embed response: " + json, e);
        }
    }
}

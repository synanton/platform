package org.synanton.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonResponseValidator {

    private static final Logger log = LoggerFactory.getLogger(JsonResponseValidator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_JSON_RETRIES = 2;

    private final LlmClient delegate;

    public JsonResponseValidator(LlmClient delegate) {
        this.delegate = delegate;
    }

    public CompletionResponse completeWithJsonValidation(CompletionRequest request) {
        for (int attempt = 1; attempt <= MAX_JSON_RETRIES + 1; attempt++) {
            CompletionResponse resp = delegate.complete(request);
            if (isValidJson(resp.text())) return resp;
            log.warn("LLM returned invalid JSON (attempt {}), retrying...", attempt);
            if (attempt <= MAX_JSON_RETRIES) {
                String retryMessage = request.userMessage() +
                        "\n\nYour last response was not valid JSON. Please return ONLY valid JSON with no extra text.";
                request = new CompletionRequest(request.model(), request.systemPrompt(), retryMessage,
                        request.temperature(), request.maxTokens());
            }
        }
        throw new LlmClientException("LLM failed to return valid JSON after " + (MAX_JSON_RETRIES + 1) + " attempts");
    }

    private boolean isValidJson(String text) {
        if (text == null || text.isBlank()) return false;
        try {
            // Try to find and parse JSON within the response
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end < 0 || start >= end) return false;
            MAPPER.readTree(text.substring(start, end + 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

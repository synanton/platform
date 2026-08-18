package org.synanton.llm;

public record LlmClientConfig(
        String baseUrl,
        String model,
        long timeoutMs,
        int maxRetries
) {
    public LlmClientConfig {
        if (baseUrl == null) baseUrl = "http://localhost:8000/v1";
        if (model == null) model = "default";
        if (timeoutMs <= 0) timeoutMs = 30_000;
        if (maxRetries < 0) maxRetries = 3;
    }
}

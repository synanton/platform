package org.synanton.llm;

public record CompletionRequest(
        String model,
        String systemPrompt,
        String userMessage,
        double temperature,
        int maxTokens
) {}

package org.synanton.llm;

public record CompletionResponse(
        String text,
        int promptTokens,
        int completionTokens
) {}

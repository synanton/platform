package org.synanton.llm;

/**
 * Completion content plus optional provider/Gateway termination metadata.
 * {@code requestId}/{@code executionId} are present only for Gateway-backed calls.
 */
public record CompletionResponse(
        String text,
        int promptTokens,
        int completionTokens,
        String finishReason,
        String requestId,
        String executionId
) {
    /** Compatibility for HTTP clients that do not expose Gateway identity. */
    public CompletionResponse(String text, int promptTokens, int completionTokens) {
        this(text, promptTokens, completionTokens, null, null, null);
    }
}

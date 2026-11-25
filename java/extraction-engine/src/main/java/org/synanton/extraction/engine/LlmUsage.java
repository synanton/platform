package org.synanton.extraction.engine;

/** Usage and termination metadata for one LLM extraction call. */
public record LlmUsage(String finishReason, int promptTokens, int completionTokens, int totalTokens) {

    public static final LlmUsage NONE = new LlmUsage("unknown", -1, -1, -1);

    public boolean hasTokenCounts() {
        return promptTokens >= 0 && completionTokens >= 0 && totalTokens >= 0;
    }

    public boolean isIncomplete() {
        return "length".equalsIgnoreCase(finishReason);
    }

    /** Gateway/OpenAI success termination. Anything else must not be accepted as clean. */
    public boolean isSuccessful() {
        return "stop".equalsIgnoreCase(finishReason);
    }
}

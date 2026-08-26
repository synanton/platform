package org.synanton.llm;

import java.util.List;

public record EmbedResponse(
        List<float[]> embeddings,
        int inputChars,
        int outputChars,
        long durationMs,
        int inputTokens,
        int outputTokens
) {
    public EmbedResponse(List<float[]> embeddings) {
        this(embeddings, 0, 0, 0, 0, 0);
    }
}

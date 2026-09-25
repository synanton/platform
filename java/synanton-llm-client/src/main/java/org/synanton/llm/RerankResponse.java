package org.synanton.llm;

import java.util.List;

public record RerankResponse(
        List<RerankResult> results,
        int inputChars,
        int outputChars,
        long durationMs,
        int inputTokens,
        int outputTokens
) {
    public record RerankResult(
            int index,
            double score,
            String passage
    ) {}
}
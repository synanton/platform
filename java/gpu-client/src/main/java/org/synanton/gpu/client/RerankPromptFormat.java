package org.synanton.gpu.client;

/**
 * How query and passages are presented to a cross-encoder reranker.
 *
 * <p>{@link #QWEN3} is required for Qwen3-Reranker. Measured on GPU-5 (2026-09-25) through the
 * GPU plane: without the template, relevance scores were <em>inverted</em> (relevant passage
 * 0.354 vs distractors 0.681/0.769). With it, 1.0 vs 0.005/0.001. vLLM serves the model with
 * the yes/no classifier (hf-overrides) but leaves the prompt template to the client.
 */
public enum RerankPromptFormat {
    /** Query and passages as-is (classic cross-encoders, e.g. bge-reranker, ms-marco-MiniLM). */
    PLAIN,
    /** Qwen3-Reranker chat template with an instruction (official model card format). */
    QWEN3;

    static final String QWEN3_PREFIX = "<|im_start|>system\nJudge whether the Document meets the requirements based on "
            + "the Query and the Instruct provided. Note that the answer can only be \"yes\" or \"no\".<|im_end|>\n"
            + "<|im_start|>user\n";
    static final String QWEN3_SUFFIX = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
    public static final String DEFAULT_INSTRUCTION =
            "Given a web search query, retrieve relevant passages that answer the query";

    public String query(String query, String instruction) {
        return switch (this) {
            case PLAIN -> query;
            case QWEN3 -> QWEN3_PREFIX + "<Instruct>: " + (instruction == null || instruction.isBlank()
                    ? DEFAULT_INSTRUCTION : instruction) + "\n<Query>: " + query + "\n";
        };
    }

    public String passage(String passage) {
        return switch (this) {
            case PLAIN -> passage;
            case QWEN3 -> "<Document>: " + passage + QWEN3_SUFFIX;
        };
    }
}

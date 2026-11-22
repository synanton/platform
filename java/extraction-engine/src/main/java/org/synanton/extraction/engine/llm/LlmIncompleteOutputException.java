package org.synanton.extraction.engine.llm;

import org.synanton.extraction.engine.LlmUsage;

/** A model response ended at the generation limit and must not be parsed or promoted. */
public final class LlmIncompleteOutputException extends IllegalStateException {

    private final LlmUsage usage;
    private final int responseChars;
    private final String responseSha256;

    public LlmIncompleteOutputException(LlmUsage usage, int responseChars, String responseSha256) {
        super("INCOMPLETE_LLM_OUTPUT finish_reason=" + usage.finishReason()
                + " prompt_tokens=" + usage.promptTokens()
                + " completion_tokens=" + usage.completionTokens()
                + " total_tokens=" + usage.totalTokens()
                + " response_chars=" + responseChars
                + " response_sha256=" + responseSha256);
        this.usage = usage;
        this.responseChars = responseChars;
        this.responseSha256 = responseSha256;
    }

    public String classification() {
        return "INCOMPLETE_LLM_OUTPUT";
    }

    public LlmUsage usage() {
        return usage;
    }

    public int responseChars() {
        return responseChars;
    }

    public String responseSha256() {
        return responseSha256;
    }
}

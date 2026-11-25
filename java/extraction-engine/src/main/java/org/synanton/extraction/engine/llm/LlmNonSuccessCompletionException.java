package org.synanton.extraction.engine.llm;

import org.synanton.extraction.engine.LlmUsage;

/** A completion ended for a reason other than normal stop/length. */
public final class LlmNonSuccessCompletionException extends IllegalStateException {
    private final LlmUsage usage;
    private final String responseSha256;

    public LlmNonSuccessCompletionException(LlmUsage usage, String responseSha256) {
        super("NON_SUCCESS_LLM_FINISH finish_reason=" + usage.finishReason()
            + " response_sha256=" + responseSha256);
        this.usage = usage;
        this.responseSha256 = responseSha256;
    }

    public String classification() {
        return "NON_SUCCESS_LLM_FINISH";
    }

    public LlmUsage usage() {
        return usage;
    }

    public String responseSha256() {
        return responseSha256;
    }
}

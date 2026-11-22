package org.synanton.extraction.engine.llm;

import org.synanton.extraction.engine.ExtractionResult;

/**
 * A response with at least one structural failure other than a clean final
 * truncation. The valid prefix may be retained for diagnostics, but the result
 * must not be counted as a successful extraction.
 */
public final class LlmCorruptedOutputException extends IllegalStateException {

    private final ExtractionResult result;

    public LlmCorruptedOutputException(ExtractionResult result) {
        super("CORRUPTED_RESPONSE quarantined=" + result.quarantined().size()
                + " accepted_entities=" + result.preEntities().size()
                + " accepted_relations=" + result.relations().size()
                + " integrity=" + result.integrity());
        this.result = result;
    }

    public String classification() {
        return "CORRUPTED_RESPONSE";
    }

    public ExtractionResult result() {
        return result;
    }
}

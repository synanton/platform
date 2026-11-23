package org.synanton.extraction.engine.provenance;

/** Fail-closed signal that compiled and rendered prompt bytes disagree. */
public final class PromptDigestMismatchException extends IllegalStateException {

    private final String compiledDigest;
    private final String renderedDigest;

    public PromptDigestMismatchException(String compiledDigest, String renderedDigest) {
        super("PROMPT_DIGEST_MISMATCH compiled=" + compiledDigest + " rendered=" + renderedDigest);
        this.compiledDigest = compiledDigest;
        this.renderedDigest = renderedDigest;
    }

    public String classification() {
        return "PROMPT_DIGEST_MISMATCH";
    }

    public String compiledDigest() {
        return compiledDigest;
    }

    public String renderedDigest() {
        return renderedDigest;
    }
}

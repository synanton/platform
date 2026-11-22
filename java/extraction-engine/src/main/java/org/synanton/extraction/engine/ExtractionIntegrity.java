package org.synanton.extraction.engine;

/**
 * Structural integrity of one extraction result.
 *
 * <p>{@link #TRUNCATED} means the provider or record-framing signal proves the
 * response ended early and the valid prefix is recoverable. {@link #CORRUPTED}
 * means at least one structural failure other than a clean final truncation was
 * observed; the prefix must not be counted as a successful extraction.
 */
public enum ExtractionIntegrity {
    CLEAN,
    TRUNCATED,
    CORRUPTED
}

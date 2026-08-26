package org.synanton.extraction.client;

/**
 * Platform-side policy when structured extraction is unavailable or returns no payload.
 */
public enum ExtractionFallbackPolicy {
    /** Fail the ingestion item; do not emit partial structured chunks. */
    STRUCTURED_REQUIRED,
    /** Run {@link LocalTikaFallbackExtractor} and continue with flat text + metadata. */
    FALLBACK_LOCAL_TIKA,
    /** Emit flat text only; metadata extraction is skipped. */
    FAIL_OPEN_TEXT_ONLY;

    public static ExtractionFallbackPolicy fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return FALLBACK_LOCAL_TIKA;
        }
        return switch (value.trim().toLowerCase()) {
            case "fail", "structured-required", "structured_required" -> STRUCTURED_REQUIRED;
            case "partial", "text-only", "text_only", "fail-open-text-only" -> FAIL_OPEN_TEXT_ONLY;
            default -> FALLBACK_LOCAL_TIKA;
        };
    }
}

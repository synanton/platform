package org.synanton.extraction.engine;

/**
 * Digest and execution provenance for one extraction call.
 *
 * <p>Prompt and schema digests follow the shared contract in
 * {@code docs/architecture/ogce-digest-contract.md}. Gateway identifiers are
 * preserved verbatim; null means historical or not available, never a fabricated value.</p>
 */
public record LlmProvenance(
        String promptVersion,
        String promptCompiledDigest,
        String promptRenderedDigest,
        String schemaVersion,
        String schemaDigest,
        String finishReason,
        String gatewayRequestId,
        String gatewayExecutionId) {

    public LlmProvenance(String promptVersion, String promptCompiledDigest,
                         String promptRenderedDigest, String schemaVersion, String schemaDigest) {
        this(promptVersion, promptCompiledDigest, promptRenderedDigest, schemaVersion, schemaDigest,
                null, null, null);
    }

    public static final LlmProvenance NONE =
            new LlmProvenance("unknown", null, null, null, null, null, null, null);
}

package org.synanton.extraction.engine;

/**
 * Digest/provenance for one extraction call.
 *
 * <p>Prompt and schema digests follow the shared contract in
 * {@code docs/architecture/ogce-digest-contract.md}. Null means historical or
 * not yet available, never "same as current".
 */
public record LlmProvenance(
        String promptVersion,
        String promptDigest,
        String schemaVersion,
        String schemaDigest) {

    public static final LlmProvenance NONE =
            new LlmProvenance("unknown", null, null, null);
}

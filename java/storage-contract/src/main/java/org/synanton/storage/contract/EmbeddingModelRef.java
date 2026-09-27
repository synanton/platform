package org.synanton.storage.contract;

import java.util.Objects;

/**
 * Embedding-model reference retained as provenance (Architecture 1.0 invariant 31).
 *
 * <p>Carried on search requests and stored alongside provenance records so any ranked
 * result can be traced to the exact model build that produced its vector.
 *
 * @param modelId model identifier; never blank
 * @param version model version; never blank
 * @param digest  content digest of the model artifact; never blank
 */
public record EmbeddingModelRef(String modelId, String version, String digest) {
    public EmbeddingModelRef {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(digest, "digest");
        if (modelId.isBlank() || version.isBlank() || digest.isBlank()) {
            throw new IllegalArgumentException("modelId, version and digest must not be blank");
        }
    }

    public static EmbeddingModelRef of(String modelId, String version, String digest) {
        return new EmbeddingModelRef(modelId, version, digest);
    }
}

package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Index statistics. The embedding fields come from the last index build in this process,
 * and are null when the index was only opened, not built. The retrieval benchmark treats a
 * dense run as valid only when {@code vector_docs == doc_count}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexStats(
        String tenant,
        @JsonProperty("doc_count") int docCount,
        @JsonProperty("index_generation") long indexGeneration,
        String status,
        @JsonProperty("embedding_model") String embeddingModel,
        @JsonProperty("embedding_dim") Integer embeddingDim,
        @JsonProperty("embedding_truncated") Boolean embeddingTruncated,
        @JsonProperty("vector_docs") Integer vectorDocs,
        @JsonProperty("dim_mismatches") Integer dimMismatches,
        @JsonProperty("missing_vectors") Integer missingVectors
) {
    public IndexStats(String tenant, int docCount, long indexGeneration, String status) {
        this(tenant, docCount, indexGeneration, status, null, null, null, null, null, null);
    }
}

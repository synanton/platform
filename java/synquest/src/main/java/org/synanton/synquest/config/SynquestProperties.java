package org.synanton.synquest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synquest")
public record SynquestProperties(
        Index index,
        Search search,
        Embedding embedding,
        Execution execution
) {
    public record Index(
            String path,
            boolean rebuildOnBootIfEmpty,
            int readerRefreshSeconds
    ) {}

    public record Search(
            int defaultTopK,
            int defaultTopKDense,
            int defaultTopKLexical,
            int defaultRrfK,
            String denseSimilarity
    ) {}

    /**
     * Execution posture (SYN-VECTOR-001 B4): deployment default for retrieval
     * legs. {@code parallel} runs lexical + dense concurrently (default);
     * {@code sequential} runs lexical first and constrains dense to its
     * candidate universe. Per-query override via the search request.
     */
    public record Execution(
            String mode
    ) {}

    /**
     * @param dim         vector dimension of the dense index ({@code EMBED_DIM}). At most
     *                    Lucene's KNN cap (1024 in Lucene 9.11).
     * @param truncateDim {@code EMBED_TRUNCATE_DIM}; 0 means off. When set it must equal
     *                    {@code dim}. Longer vectors (e.g. 2048-dim Matryoshka models) are then
     *                    cut to the first {@code dim} components and L2-renormalised, the same
     *                    way at index build and at query time. Without it, any length other
     *                    than {@code dim} is a dimension mismatch.
     */
    public record Embedding(
            String model,
            int dim,
            boolean normaliseL2,
            int truncateDim
    ) {
        // Keep a single (canonical) constructor: a second one makes Spring Boot unable to pick
        // the bind constructor and silently drops every synquest.embedding.* property.
    }

    public SynquestProperties {
        if (index == null) index = new Index("./data/synquest", true, 30);
        if (search == null) search = new Search(20, 100, 100, 60, "COSINE");
        if (embedding == null) embedding = new Embedding("bge-base-en-v1.5", 768, true, 0);
        if (execution == null) execution = new Execution("parallel");
    }
}

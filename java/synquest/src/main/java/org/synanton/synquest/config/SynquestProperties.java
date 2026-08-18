package org.synanton.synquest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synquest")
public record SynquestProperties(
        Index index,
        Search search,
        Embedding embedding
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

    public record Embedding(
            String model,
            int dim,
            boolean normaliseL2
    ) {}

    public SynquestProperties {
        if (index == null) index = new Index("./data/synquest", true, 30);
        if (search == null) search = new Search(20, 100, 100, 60, "COSINE");
        if (embedding == null) embedding = new Embedding("bge-base-en-v1.5", 768, true);
    }
}

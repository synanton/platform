package org.synanton.synflux.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synflux")
public record SynfluxProperties(
    Ingest ingest,
    Pipeline pipeline,
    Enrichment enrichment,
    Embedding embedding,
    Kafka kafka
) {
    public record Ingest(int parallelism, long maxFileSizeBytes, Chunk chunk) {
        public record Chunk(String strategy, int targetTokens, int overlapTokens) {}
    }
    public record Pipeline(boolean enrichmentEnabled, boolean embeddingEnabled, String extractionServiceUrl) {}
    public record Enrichment(String llmBaseUrl, String modelId, int parallelism) {}
    public record Embedding(String embedBaseUrl, String modelId, int batchSize) {}
    public record Kafka(int consumerThreads, int maxRetries, long retryBackoffMs) {}
}

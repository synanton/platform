package org.synanton.synflux.config;

import org.synanton.extraction.client.ExtractionClientMetrics;
import org.synanton.extraction.client.ExtractionClientProperties;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.client.LocalTikaFallbackExtractor;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.llm.HttpLlmClient;
import org.synanton.llm.LlmClient;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.stage.*;
import org.synanton.synvault.port.ContentPullPort;
import org.synanton.synvault.port.ObjectStorePort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SynfluxProperties.class, ExtractionClientProperties.class})
public class SynfluxConfig {

    private static final String HOT_BUCKET = "synanton-hot";

    @Bean
    public AcquireStage acquireStage(ContentPullPort pullPort) {
        return new AcquireStage(pullPort);
    }

    @Bean
    public ExtractionStage extractionStage(
            ExtractionPlaneClient extractionClient,
            LocalTikaFallbackExtractor fallbackExtractor,
            ExtractionClientProperties extractionClientProperties,
            ExtractionClientMetrics extractionClientMetrics,
            ObjectStorePort objectStore) {
        return new ExtractionStage(
                extractionClient,
                fallbackExtractor,
                extractionClientProperties.fallbackPolicy(),
                extractionClientMetrics,
                objectStore,
                HOT_BUCKET);
    }

    @Bean
    public SemanticChunkStage semanticChunkStage(SynfluxProperties props) {
        SynfluxProperties.Ingest.Chunk chunk = props.ingest() != null ? props.ingest().chunk() : null;
        int maxTokens = chunk != null ? chunk.targetTokens() : 512;
        return new SemanticChunkStage(ChunkerConfig.of(maxTokens));
    }

    @Bean
    public PipelineStage<ChunkedDocument, ChunkedDocument> enrichStage(
            SynfluxProperties props, IngestionCacheClient cacheClient) {
        if (props.pipeline().enrichmentEnabled()) {
            LlmClient llm = new HttpLlmClient(props.enrichment().llmBaseUrl(), 3);
            return new EnrichStage(llm, cacheClient,
                props.enrichment().modelId(), props.enrichment().parallelism());
        }
        return new NoOpEnrichmentStage();
    }

    @Bean
    public PipelineStage<ChunkedDocument, ChunkedDocument> embedStage(
            SynfluxProperties props, IngestionCacheClient cacheClient) {
        if (props.pipeline().embeddingEnabled()) {
            LlmClient embedClient = new HttpLlmClient(props.embedding().embedBaseUrl(), 3);
            return new EmbedStage(embedClient, cacheClient,
                props.embedding().modelId(), props.embedding().batchSize());
        }
        return new NoOpEmbeddingStage();
    }

    @Bean
    public PersistStage persistStage(IngestionCacheClient cacheClient, ObjectStorePort objectStore,
                                     SynfluxProperties props) {
        return new PersistStage(cacheClient, objectStore, HOT_BUCKET);
    }
}

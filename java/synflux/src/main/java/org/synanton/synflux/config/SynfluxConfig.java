package org.synanton.synflux.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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
import synanton.extraction.v1.ExtractionServiceGrpc;

@Configuration
@EnableConfigurationProperties(SynfluxProperties.class)
public class SynfluxConfig {

    private static final String HOT_BUCKET = "synanton-hot";

    @Bean
    public AcquireStage acquireStage(ContentPullPort pullPort) {
        return new AcquireStage(pullPort);
    }

    @Bean
    public ExtractionStage extractionStage(SynfluxProperties props, ObjectStorePort objectStore) {
        String url = props.pipeline() != null ? props.pipeline().extractionServiceUrl() : null;
        ExtractionServiceGrpc.ExtractionServiceBlockingStub stub = null;
        if (url != null && !url.isBlank()) {
            ManagedChannel channel = ManagedChannelBuilder.forTarget(url).usePlaintext().build();
            stub = ExtractionServiceGrpc.newBlockingStub(channel);
        }
        return new ExtractionStage(stub, objectStore, HOT_BUCKET);
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

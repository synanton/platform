package org.synanton.synflux.config;

import org.synanton.contentcache.ContentStore;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.llm.LlmExtractionAdapter;
import org.synanton.extraction.engine.regex.RegexExtractorAdapter;
import org.synanton.contentcache.FileSystemContentStore;
import org.synanton.common.kafka.KafkaClientConfig;
import org.synanton.extraction.client.ExtractionClientMetrics;
import org.synanton.extraction.client.ExtractionClientProperties;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.client.LocalTikaFallbackExtractor;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.gpu.client.GpuPlaneEmbedClient;
import org.synanton.llm.HttpLlmClient;
import org.synanton.llm.LlmClient;
import org.synanton.synflux.annotation.AnnotationRule;
import org.synanton.synflux.annotation.AnnotationsServiceClient;
import org.synanton.synflux.annotation.HttpAnnotationsServiceClient;
import org.synanton.synflux.annotation.KeywordAnnotationRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.extraction.graph.GraphWriteClient;
import org.synanton.synflux.extraction.graph.GraphWriter;
import org.synanton.synflux.extraction.graph.HttpRelixGraphWriteClient;
import org.synanton.synflux.extraction.graph.GraphWriteTokenMinter;
import org.synanton.synflux.extraction.graph.HttpSyntologyRelationValidator;
import org.synanton.synflux.extraction.graph.RelationValidator;
import org.synanton.synflux.resolution.CanonicalRelationValidator;
import org.synanton.synflux.resolution.DefaultCanonicalRelationValidator;
import org.synanton.synflux.resolution.HttpRelixResolutionLookup;
import org.synanton.common.jwt.ServiceTokenMinter;
import org.synanton.common.resolution.ExtractedMentionGraphReadPort;
import org.synanton.common.resolution.ResolutionGraphLookup;
import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.stage.*;
import org.synanton.synflux.lifecycle.FilesystemFixturePolicy;
import org.synanton.synflux.lifecycle.FixtureIngestionService;
import org.synanton.synvault.port.ContentPullPort;
import org.synanton.synvault.port.ObjectStorePort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties({SynfluxProperties.class, ExtractionClientProperties.class,
        ExtractionEngineProperties.class, ExtractionConsumerProperties.class,
        ExtractionGraphProperties.class})
@Import(KafkaClientConfig.class)
public class SynfluxConfig {

    private static final String HOT_BUCKET = "synanton-hot";

    @Bean
    public Clock synfluxClock() {
        return Clock.systemUTC();
    }

    @Bean
    public FilesystemFixturePolicy filesystemFixturePolicy(
            @Value("${SYNFLUX_FIXTURE_ROOT}") String fixtureRoot) {
        return new FilesystemFixturePolicy(Path.of(fixtureRoot));
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService sourceIngestionExecutor(SynfluxProperties properties) {
        return Executors.newFixedThreadPool(properties.ingest().parallelism());
    }

    // The slice's lifecycle service has no Spring annotation and was only ever
    // constructed manually in tests — the composed boot needs the bean.
    @Bean
    public FixtureIngestionService fixtureIngestionService(
            org.synanton.ingestioncache.client.IngestionCacheClient cache, Clock clock,
            org.synanton.eventing.contract.EventTypeRegistry eventTypeRegistry) {
        return new FixtureIngestionService(cache, clock, eventTypeRegistry);
    }

    @Bean
    @ConditionalOnProperty(name = "SYNFLUX_CONTENT_CACHE_BACKEND", havingValue = "filesystem")
    @ConditionalOnMissingBean(ContentStore.class)
    public ContentStore filesystemContentStore(
            @Value("${SYNFLUX_CONTENT_CACHE_ROOT}") String contentCacheRoot,
            FilesystemFixturePolicy fixturePolicy) {
        FileSystemContentStore store = new FileSystemContentStore(Path.of(contentCacheRoot));
        fixturePolicy.validateSeparateContentCacheRoot(store.root());
        return store;
    }

    @Bean
    public AcquireStage acquireStage(ContentPullPort pullPort, ContentStore contentStore) {
        return new AcquireStage(pullPort, contentStore);
    }

    /**
     * RAKG extraction engine switch: regex is the deterministic default so
     * ingestion keeps working without the GPU plane; llm calls the vLLM
     * extraction endpoint (ADR-022). Unknown providers fail loud at startup.
     */
    @Bean
    @ConditionalOnMissingBean(ExtractionEngineAdapter.class)
    public ExtractionEngineAdapter extractionEngineAdapter(ExtractionEngineProperties properties) {
        String provider = properties.provider() == null
                ? "regex"
                : properties.provider().strip().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "regex" -> new RegexExtractorAdapter();
            case "llm" -> new LlmExtractionAdapter(
                    properties.endpoint() == null || properties.endpoint().isBlank()
                            ? "http://localhost:8000/v1"
                            : properties.endpoint(),
                    properties.model() == null || properties.model().isBlank()
                            ? "qwen3-extraction"
                            : properties.model(),
                    Duration.ofSeconds(properties.timeoutSeconds() == null
                            ? 120
                            : Math.max(1, properties.timeoutSeconds())));
            default -> throw new IllegalStateException(
                    "unknown synflux.extraction-engine.provider: " + provider + " (want regex|llm)");
        };
    }

    @Bean
    public RelationValidator relationValidator(ExtractionGraphProperties properties,
                                               @Value("${SYNANTON_JWT_SECRET}") String jwtSecret) {
        return new HttpSyntologyRelationValidator(properties.syntologyUrlOrDefault(), graphMapper(),
                new ServiceTokenMinter(jwtSecret));
    }

    @Bean
    public GraphWriteClient graphWriteClient(ExtractionGraphProperties properties,
                                             @Value("${SYNANTON_JWT_SECRET}") String jwtSecret) {
        return new HttpRelixGraphWriteClient(properties.relixUrlOrDefault(), graphMapper(),
                new GraphWriteTokenMinter(jwtSecret));
    }

    @Bean
    public GraphWriter graphWriter(IngestionCacheClient cacheClient, RelationValidator validator,
                                   GraphWriteClient graphWriteClient, ExtractionGraphProperties properties,
                                   Clock clock,
                                   org.springframework.beans.factory.ObjectProvider<org.synanton.ingestioncache.activation.SourceActivationStore> activationStore) {
        return new GraphWriter(cacheClient, validator, graphWriteClient, properties, clock,
                activationStore.getIfAvailable());
    }

    private static ObjectMapper graphMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public HttpRelixResolutionLookup resolutionGraphLookup(
            @Value("${RELIX_BASE_URL:http://relix:8080}") String relixBaseUrl,
            @Value("${SYNANTON_JWT_SECRET}") String jwtSecret) {
        return new HttpRelixResolutionLookup(relixBaseUrl,
                new ServiceTokenMinter(jwtSecret), new ObjectMapper());
    }

    @Bean
    public CanonicalRelationValidator canonicalRelationValidator(
            ResolutionGraphLookup resolutionGraphLookup,
            ExtractedMentionGraphReadPort extractedReadPort,
            RelationValidator relationValidator,
            IngestionCacheClient cacheClient, Clock clock) {
        return new DefaultCanonicalRelationValidator(resolutionGraphLookup, extractedReadPort,
                relationValidator, cacheClient, clock);
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

    /**
     * RQ1 chunker switch: {@code ingest.chunk.strategy} selects the chunking
     * ({@code semantic} default = current behavior, {@code fixed-window} =
     * word-count windows, {@code hierarchical} = one chunk per section for
     * the RQ1 comparison legs, {@code sentence} = sentence-aligned RAKG chunks).
     * Unknown values fail loud at startup, never silently fall back.
     */
    @Bean
    public PipelineStage<org.synanton.synflux.domain.ParsedDocument,
            org.synanton.synflux.domain.ChunkedDocument> chunkStage(SynfluxProperties props) {
        SynfluxProperties.Ingest.Chunk chunk = props.ingest() != null ? props.ingest().chunk() : null;
        String strategy = chunk != null && chunk.strategy() != null ? chunk.strategy() : "semantic";
        int targetTokens = chunk != null ? chunk.targetTokens() : 512;
        int overlapTokens = chunk != null ? chunk.overlapTokens() : 50;
        return switch (strategy) {
            case "fixed-window" -> new ChunkStage(targetTokens, overlapTokens);
            case "hierarchical" -> new HierarchicalChunkStage();
            case "semantic" -> new SemanticChunkStage(ChunkerConfig.of(targetTokens));
            case "sentence" -> new SentenceChunkStage(targetTokens, 1);
            default -> throw new IllegalStateException(
                    "unknown ingest.chunk.strategy: " + strategy + " (want fixed-window|semantic|hierarchical|sentence)");
        };
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
            SynfluxProperties props, IngestionCacheClient cacheClient,
            Optional<GpuPlaneEmbedClient> gpuPlaneEmbedClient) {
        if (props.pipeline().embeddingEnabled() && gpuPlaneEmbedClient.isPresent()) {
            // gpu-plane profile: gRPC synanton.gpu.v1 (mTLS), tenant-scoped, fail-closed per document.
            return new EmbedStage(gpuPlaneEmbedClient.get(), cacheClient,
                props.embedding().modelId(), props.embedding().batchSize(), true);
        }
        if (props.pipeline().embeddingEnabled()) {
            LlmClient embedClient = new HttpLlmClient(props.embedding().embedBaseUrl(), 3);
            return new EmbedStage(embedClient, cacheClient,
                props.embedding().modelId(), props.embedding().batchSize());
        }
        return new NoOpEmbeddingStage();
    }

    @Bean
    public PersistStage persistStage(IngestionCacheClient cacheClient) {
        return new PersistStage(cacheClient);
    }

    @Bean
    public AnnotationsServiceClient annotationsServiceClient(SynfluxProperties props) {
        return new HttpAnnotationsServiceClient(props.annotation().serviceBaseUrl());
    }

    /**
     * Demo/initial producer, mirroring the design's own §8 example
     * (definition_id: payment-detection, producer: payment-rule-engine, producer_version: 4.2).
     * Real deployments register definitions/versions with the {@code annotations} service instead
     * of hardcoding rules here; this bean is the seam Resolutor/Equalix (AAP-2) will extend.
     */
    @Bean
    public List<AnnotationRule> annotationRules() {
        return List.of(new KeywordAnnotationRule(
                "payment-detection", 4, "TAG", "billing", "payment",
                "payment-rule-engine", "4.2",
                List.of("invoice number", "payment reference", "payment terms")));
    }

    @Bean
    public PipelineStage<ChunkedDocument, ChunkedDocument> annotationStage(
            SynfluxProperties props, IngestionCacheClient cacheClient,
            AnnotationsServiceClient annotationsServiceClient, List<AnnotationRule> annotationRules) {
        if (props.pipeline().annotationEnabled()) {
            return new AnnotationStage(
                    cacheClient, annotationsServiceClient, annotationRules,
                    props.annotation().producer(), props.annotation().producerVersion());
        }
        return new NoOpAnnotationStage();
    }
}

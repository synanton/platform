package org.synanton.synflux.pipeline.stage;

import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.llm.LlmClient;
import org.synanton.llm.EmbedRequest;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class EmbedStage implements PipelineStage<ChunkedDocument, ChunkedDocument> {

    private static final Logger log = LoggerFactory.getLogger(EmbedStage.class);
    private final LlmClient embedClient;
    private final IngestionCacheClient cacheClient;
    private final String modelId;
    private final int batchSize;

    public EmbedStage(LlmClient embedClient, IngestionCacheClient cacheClient, String modelId, int batchSize) {
        this.embedClient = embedClient;
        this.cacheClient = cacheClient;
        this.modelId = modelId;
        this.batchSize = batchSize;
    }

    @Override
    public String name() { return "embed"; }

    @Override
    public ChunkedDocument apply(ChunkedDocument doc, StageContext ctx) {
        var acquired = doc.parsed().acquired();
        String tenantId = ctx.tenant();
        UUID contentRefId = acquired.contentRefId();
        List<SemanticChunk> chunks = doc.chunks();

        // Filter uncached chunks
        List<SemanticChunk> toEmbed = new ArrayList<>();
        for (SemanticChunk c : chunks) {
            var cached = cacheClient.readEmbeddingByChunkHash(tenantId, c.sha256(), modelId);
            if (cached.isEmpty()) toEmbed.add(c);
        }

        // Process in batches
        for (int i = 0; i < toEmbed.size(); i += batchSize) {
            List<SemanticChunk> batch = toEmbed.subList(i, Math.min(i + batchSize, toEmbed.size()));
            List<String> texts = batch.stream().map(SemanticChunk::text).collect(Collectors.toList());
            try {
                var resp = embedClient.embed(new EmbedRequest(modelId, texts));
                for (int j = 0; j < batch.size(); j++) {
                    SemanticChunk c = batch.get(j);
                    float[] embedding = resp.embeddings().get(j);
                    cacheClient.upsertEmbedding(new EmbeddingRow(
                        tenantId, contentRefId, c.ordinal(), modelId, c.sha256(),
                        embedding, embedding.length, Instant.now()
                    ));
                }
            } catch (Exception e) {
                log.warn("Embedding failed for batch at offset {}: {}", i, e.getMessage());
            }
        }

        log.info("Embedding complete for ref={}: {} chunks", contentRefId, chunks.size());
        return doc;
    }
}

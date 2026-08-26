package org.synanton.synflux.pipeline.stage;

import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.llm.LlmClient;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.StageUsage;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.StageUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
        AtomicLong inputChars = new AtomicLong();
        AtomicLong outputChars = new AtomicLong();
        AtomicLong inputTokens = new AtomicLong();
        AtomicLong outputTokens = new AtomicLong();
        AtomicLong durationMs = new AtomicLong();

        StageUsageTracker.TimedResult<ChunkedDocument> timed = StageUsageTracker.time(() ->
            embed(doc, ctx, inputChars, outputChars, inputTokens, outputTokens, durationMs));

        ctx.usage().record(new StageUsage(
            name(), timed.wallMs(), timed.cpuNs(), modelId,
            inputChars.get(), outputChars.get(),
            (int) inputTokens.get(), (int) outputTokens.get(), 0, 0));
        return timed.value();
    }

    private ChunkedDocument embed(
            ChunkedDocument doc,
            StageContext ctx,
            AtomicLong inputChars,
            AtomicLong outputChars,
            AtomicLong inputTokens,
            AtomicLong outputTokens,
            AtomicLong durationMs) {

        var acquired = doc.parsed().acquired();
        String tenantId = ctx.tenant();
        UUID contentRefId = acquired.contentRefId();
        List<SemanticChunk> chunks = doc.chunks();

        List<SemanticChunk> toEmbed = new ArrayList<>();
        for (SemanticChunk c : chunks) {
            var cached = cacheClient.readEmbeddingByChunkHash(tenantId, c.sha256(), modelId);
            if (cached.isEmpty()) {
                toEmbed.add(c);
            }
        }

        for (int i = 0; i < toEmbed.size(); i += batchSize) {
            List<SemanticChunk> batch = toEmbed.subList(i, Math.min(i + batchSize, toEmbed.size()));
            List<String> texts = batch.stream().map(SemanticChunk::text).collect(Collectors.toList());
            int batchInputChars = texts.stream().mapToInt(String::length).sum();
            try {
                EmbedResponse resp = embedClient.embed(new EmbedRequest(modelId, texts));
                inputChars.addAndGet(resp.inputChars() > 0 ? resp.inputChars() : batchInputChars);
                outputChars.addAndGet(resp.outputChars());
                inputTokens.addAndGet(resp.inputTokens());
                outputTokens.addAndGet(resp.outputTokens());
                durationMs.addAndGet(resp.durationMs());
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

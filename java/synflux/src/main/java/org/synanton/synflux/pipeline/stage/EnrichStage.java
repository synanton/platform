package org.synanton.synflux.pipeline.stage;

import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.AnalysisRow;
import org.synanton.llm.LlmClient;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.StageUsage;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.StageUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class EnrichStage implements PipelineStage<ChunkedDocument, ChunkedDocument> {

    private static final Logger log = LoggerFactory.getLogger(EnrichStage.class);
    private static final String PROMPT_VERSION = "v1.0";

    private final LlmClient llmClient;
    private final IngestionCacheClient cacheClient;
    private final String modelId;
    private final int parallelism;

    public EnrichStage(LlmClient llmClient, IngestionCacheClient cacheClient, String modelId, int parallelism) {
        this.llmClient = llmClient;
        this.cacheClient = cacheClient;
        this.modelId = modelId;
        this.parallelism = parallelism;
    }

    @Override
    public String name() { return "enrich"; }

    @Override
    public ChunkedDocument apply(ChunkedDocument doc, StageContext ctx) {
        AtomicLong inputChars = new AtomicLong();
        AtomicLong outputChars = new AtomicLong();
        AtomicLong inputTokens = new AtomicLong();
        AtomicLong outputTokens = new AtomicLong();

        StageUsageTracker.TimedResult<ChunkedDocument> timed = StageUsageTracker.time(() ->
            enrich(doc, ctx, inputChars, outputChars, inputTokens, outputTokens));

        ctx.usage().record(new StageUsage(
            name(), timed.wallMs(), timed.cpuNs(), modelId,
            inputChars.get(), outputChars.get(),
            (int) inputTokens.get(), (int) outputTokens.get(), 0, 0));
        return timed.value();
    }

    private ChunkedDocument enrich(
            ChunkedDocument doc,
            StageContext ctx,
            AtomicLong inputChars,
            AtomicLong outputChars,
            AtomicLong inputTokens,
            AtomicLong outputTokens) {

        var acquired = doc.parsed().acquired();
        String tenantId = ctx.tenant();
        UUID contentRefId = acquired.contentRefId();

        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        List<Future<String>> pass1Futures = new ArrayList<>();
        List<String> pass1Results = new ArrayList<>();

        for (SemanticChunk chunk : doc.chunks()) {
            final int ordinal = chunk.ordinal();
            final String chunkText = chunk.text();
            pass1Futures.add(pool.submit(() -> {
                String inputHash = computeInputHash("pass1", PROMPT_VERSION, modelId, chunkText);
                var cached = cacheClient.readAnalysisByInputHash(tenantId, inputHash);
                if (cached.isPresent()) {
                    return cached.get().analysisJson();
                }

                String systemPrompt = "You are a document analyzer. Return ONLY valid JSON with keys: summary (string), entity_strings (array of strings).";
                String userMessage = "Analyze this text chunk:\n\n" + chunkText;
                inputChars.addAndGet(systemPrompt.length() + userMessage.length());
                CompletionResponse resp = llmClient.complete(
                    new CompletionRequest(modelId, systemPrompt, userMessage, 0.0, 300));
                inputTokens.addAndGet(resp.promptTokens());
                outputTokens.addAndGet(resp.completionTokens());
                outputChars.addAndGet(resp.text() == null ? 0 : resp.text().length());
                String json = resp.text();

                cacheClient.upsertAnalysis(new AnalysisRow(
                    tenantId, contentRefId, ordinal, 1, modelId, PROMPT_VERSION, json, inputHash, Instant.now()
                ));
                return json;
            }));
        }

        for (var future : pass1Futures) {
            try {
                pass1Results.add(future.get());
            } catch (Exception e) {
                log.warn("Pass 1 enrichment failed: {}", e.getMessage());
                pass1Results.add("{}");
            }
        }
        pool.shutdown();

        try {
            String combinedSummaries = String.join("\n---\n", pass1Results);
            String inputHash = computeInputHash("pass2", PROMPT_VERSION, modelId, combinedSummaries);
            var cached = cacheClient.readAnalysisByInputHash(tenantId, inputHash);
            if (cached.isPresent()) {
                cached.get().analysisJson();
            } else {
                String systemPrompt = "You are a document analyzer. Return ONLY valid JSON with keys: typed_entities (array of {label, type, confidence}), relations (array of {from, to, verb, confidence}).";
                String userMessage = "Extract entities and relations from these chunk summaries:\n\n" + combinedSummaries;
                inputChars.addAndGet(systemPrompt.length() + userMessage.length());
                CompletionResponse resp = llmClient.complete(
                    new CompletionRequest(modelId, systemPrompt, userMessage, 0.0, 800));
                inputTokens.addAndGet(resp.promptTokens());
                outputTokens.addAndGet(resp.completionTokens());
                outputChars.addAndGet(resp.text() == null ? 0 : resp.text().length());
                cacheClient.upsertAnalysis(new AnalysisRow(
                    tenantId, contentRefId, -1, 2, modelId, PROMPT_VERSION, resp.text(), inputHash, Instant.now()
                ));
            }
            log.info("Enrichment complete for ref={}", contentRefId);
        } catch (Exception e) {
            log.warn("Pass 2 enrichment failed for ref={}: {}", contentRefId, e.getMessage());
        }

        return doc;
    }

    private static String computeInputHash(String pass, String promptVersion, String modelId, String content) {
        String key = pass + "|" + promptVersion + "|" + modelId + "|" + content;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

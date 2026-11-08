package org.synanton.synflux.pipeline.stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.synanton.extraction.engine.chunking.SentenceChunker;
import org.synanton.synflux.chunking.SemanticChunker;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.domain.StageUsage;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.StageUsageTracker;

/**
 * RAKG Phase 0: sentence-boundary chunking. Whole sentences are grouped up to a
 * token budget and consecutive chunks overlap by one sentence, so the LLM
 * extractor receives semantic context instead of fixed-window cuts.
 */
public class SentenceChunkStage implements PipelineStage<ParsedDocument, ChunkedDocument> {

    private final int maxTokens;
    private final int overlapSentences;

    public SentenceChunkStage(int maxTokens, int overlapSentences) {
        this.maxTokens = Math.max(1, maxTokens);
        this.overlapSentences = Math.max(0, overlapSentences);
    }

    @Override
    public String name() {
        return "sentence-chunk";
    }

    @Override
    public ChunkedDocument apply(ParsedDocument doc, StageContext ctx) {
        StageUsageTracker.TimedResult<ChunkedDocument> timed = StageUsageTracker.time(() -> chunk(doc));
        long outputChars = timed.value().chunks().stream()
                .mapToLong(chunk -> chunk.content() == null ? 0 : chunk.content().length())
                .sum();
        ctx.usage().record(new StageUsage(
                name(), timed.wallMs(), timed.cpuNs(), null, 0, outputChars, 0, 0, 0, 0));
        return timed.value();
    }

    private ChunkedDocument chunk(ParsedDocument doc) {
        String documentId = doc.acquired().contentRefId().toString();
        List<SemanticChunk> chunks = new ArrayList<>();
        int ordinal = 0;
        for (SentenceChunker.Chunk chunk : SentenceChunker.chunk(doc.text(), maxTokens, overlapSentences)) {
            String content = chunk.text();
            chunks.add(new SemanticChunk(
                    documentId + "-s" + ordinal, documentId, ordinal, SemanticChunk.ChunkType.PARAGRAPH,
                    content, null, List.of(), null, List.of(), -1, -1,
                    SemanticChunker.estimateTokens(content), false, Map.of(),
                    SemanticChunk.PUBLIC_ONLY, SemanticChunker.sha256(content),
                    chunk.charStart(), chunk.charEnd()));
            ordinal++;
        }
        return new ChunkedDocument(doc, chunks);
    }
}

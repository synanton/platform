package org.synanton.synflux.pipeline.stage;

import org.synanton.synflux.chunking.DocumentStructureBuilder;
import org.synanton.synflux.chunking.SemanticChunker;
import org.synanton.synflux.chunking.SectionNode;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.domain.SemanticChunk.ChunkType;
import org.synanton.synflux.domain.StageUsage;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.StageUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import synanton.extraction.v1.DocumentPayload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SemanticChunkStage implements PipelineStage<ParsedDocument, ChunkedDocument> {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunkStage.class);

    private final ChunkerConfig config;
    private final DocumentStructureBuilder structureBuilder;
    private final SemanticChunker chunker;

    public SemanticChunkStage(ChunkerConfig config) {
        this.config = config;
        this.structureBuilder = new DocumentStructureBuilder();
        this.chunker = new SemanticChunker();
    }

    @Override
    public String name() { return "semantic-chunk"; }

    @Override
    public ChunkedDocument apply(ParsedDocument doc, StageContext ctx) {
        StageUsageTracker.TimedResult<ChunkedDocument> timed = StageUsageTracker.time(() -> chunk(doc));
        long outputChars = timed.value().chunks().stream()
            .mapToLong(c -> c.content() == null ? 0 : c.content().length())
            .sum();
        ctx.usage().record(new StageUsage(
            name(), timed.wallMs(), timed.cpuNs(), null,
            0, outputChars, 0, 0, 0, 0));
        return timed.value();
    }

    private ChunkedDocument chunk(ParsedDocument doc) {
        DocumentPayload payload = doc.documentPayload();
        String documentId = doc.acquired().contentRefId().toString();

        List<SemanticChunk> chunks;

        if (payload != null && !payload.getElementsList().isEmpty()) {
            log.debug("Semantic chunking {} elements for ref={}",
                payload.getElementsCount(), doc.acquired().contentRefId());

            List<SectionNode> sections = structureBuilder.build(payload.getElementsList());
            chunks = new ArrayList<>(chunker.chunk(sections, documentId, config));

            if (chunks.isEmpty() && !doc.text().isBlank()) {
                chunks = flatTextFallback(doc.text(), documentId);
            }
        } else {
            log.debug("No structured payload for ref={}; using flat-text fallback",
                doc.acquired().contentRefId());
            chunks = flatTextFallback(doc.text(), documentId);
        }

        return new ChunkedDocument(doc, chunks);
    }

    private List<SemanticChunk> flatTextFallback(String text, String documentId) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String[] words = text.split("\\s+");
        List<SemanticChunk> chunks = new ArrayList<>();
        int start = 0;
        int ordinal = 0;

        while (start < words.length) {
            int end = Math.min(start + config.maxTokensPerChunk(), words.length);
            String chunkText = String.join(" ", Arrays.copyOfRange(words, start, end));
            chunks.add(fallbackChunk(chunkText, documentId, ordinal++));
            if (end == words.length) {
                break;
            }
            int overlap = Math.min(config.minChunkTokens(), config.maxTokensPerChunk() / 10);
            start = end - overlap;
        }
        return chunks;
    }

    private static SemanticChunk fallbackChunk(String text, String documentId, int ordinal) {
        return new SemanticChunk(
            documentId + "-c" + ordinal, documentId, ordinal, ChunkType.FALLBACK,
            text, null,
            List.of(), null, List.of(),
            -1, -1,
            SemanticChunker.estimateTokens(text),
            false,
            Map.of(),
            SemanticChunk.PUBLIC_ONLY,
            SemanticChunker.sha256(text)
        );
    }
}

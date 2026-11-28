package org.synanton.synflux.pipeline.stage;

import org.synanton.synflux.chunking.CanonicalTextEvidence;
import org.synanton.synflux.chunking.ChunkTextPolicy;
import org.synanton.synflux.chunking.DocumentStructureBuilder;
import org.synanton.synflux.chunking.SemanticChunker;
import org.synanton.synflux.chunking.SectionNode;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.domain.StageUsage;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.StageUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.extraction.v1.DocumentPayload;

import java.util.ArrayList;
import java.util.List;

public class SemanticChunkStage implements PipelineStage<ParsedDocument, ChunkedDocument> {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunkStage.class);

    private final ChunkerConfig config;
    private final ChunkTextPolicy policy;
    private final DocumentStructureBuilder structureBuilder;
    private final SemanticChunker chunker;

    public SemanticChunkStage(ChunkerConfig config) {
        this(config, ChunkTextPolicy.RETRIEVAL);
    }

    public SemanticChunkStage(ChunkerConfig config, ChunkTextPolicy policy) {
        this.config = config;
        this.policy = policy;
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
            chunks = new ArrayList<>(chunker.chunk(
                sections, documentId, config, policy, CanonicalTextEvidence.from(payload)));

            if (chunks.isEmpty()) {
                throw new StructuredChunkingRequiredException(
                    "Structured payload produced no usable chunks for ref=" + documentId);
            }
        } else {
            throw new StructuredChunkingRequiredException(
                "Structured payload missing for ref=" + documentId);
        }

        return new ChunkedDocument(doc, chunks);
    }

}

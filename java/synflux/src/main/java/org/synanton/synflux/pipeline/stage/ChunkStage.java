package org.synanton.synflux.pipeline.stage;

import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.domain.SemanticChunk.ChunkType;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Legacy fixed-window token chunker, kept as a fallback.
 * Prefer {@link SemanticChunkStage} for structure-aware chunking (v1.22).
 */
public class ChunkStage implements PipelineStage<ParsedDocument, ChunkedDocument> {

    private final int targetTokens;
    private final int overlapTokens;

    public ChunkStage(int targetTokens, int overlapTokens) {
        this.targetTokens = targetTokens;
        this.overlapTokens = overlapTokens;
    }

    @Override
    public String name() { return "chunk"; }

    @Override
    public ChunkedDocument apply(ParsedDocument doc, StageContext ctx) {
        String[] words = doc.text().split("\\s+");
        List<SemanticChunk> chunks = new ArrayList<>();
        String documentId = doc.acquired().contentRefId().toString();
        int start   = 0;
        int ordinal = 0;

        while (start < words.length) {
            int end = Math.min(start + targetTokens, words.length);
            String text = String.join(" ", Arrays.copyOfRange(words, start, end));
            chunks.add(naiveChunk(text, documentId, ordinal++));
            if (end == words.length) break;
            start = end - overlapTokens;
        }
        if (chunks.isEmpty() && !doc.text().isBlank()) {
            chunks.add(naiveChunk(doc.text(), documentId, 0));
        }
        return new ChunkedDocument(doc, chunks);
    }

    private static SemanticChunk naiveChunk(String text, String documentId, int ordinal) {
        return new SemanticChunk(
            documentId + "-c" + ordinal, documentId, ordinal, ChunkType.FALLBACK,
            text, null,
            List.of(), null, List.of(),
            -1, -1,
            Math.max(1, text.length() / 4),
            false,
            Map.of(),
            sha256(text)
        );
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

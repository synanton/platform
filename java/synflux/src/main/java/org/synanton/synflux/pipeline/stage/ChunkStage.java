package org.synanton.synflux.pipeline.stage;

import org.synanton.synflux.domain.Chunk;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

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
        int targetTokens = this.targetTokens;
        int overlapTokens = this.overlapTokens;

        String[] words = doc.text().split("\\s+");
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int ordinal = 0;
        while (start < words.length) {
            int end = Math.min(start + targetTokens, words.length);
            String chunkText = String.join(" ", Arrays.copyOfRange(words, start, end));
            chunks.add(new Chunk(ordinal++, chunkText, sha256(chunkText)));
            if (end == words.length) break;
            start = end - overlapTokens;
        }
        if (chunks.isEmpty() && !doc.text().isBlank()) {
            // Ensure at least one chunk if text exists
            chunks.add(new Chunk(0, doc.text(), sha256(doc.text())));
        }
        return new ChunkedDocument(doc, chunks);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

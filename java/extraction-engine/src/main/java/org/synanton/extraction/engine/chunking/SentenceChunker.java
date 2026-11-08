package org.synanton.extraction.engine.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sentence-aligned chunking for RAKG (Phase 0). Sentences are never split; a
 * chunk is a run of whole sentences up to {@code maxTokens} (chars/4). A single
 * oversize sentence becomes its own chunk. Consecutive chunks overlap by
 * {@code overlapSentences} sentences so a relation spanning a boundary is not
 * lost. Shared by synflux's sentence chunk stage and the evaluator CLI.
 */
public final class SentenceChunker {

    private static final Pattern BOUNDARY = Pattern.compile("(?<=[.!?])\\s+|\\n\\s*\\n");

    private SentenceChunker() {
    }

    public record Chunk(String text, int charStart, int charEnd) {}

    public static List<Chunk> chunk(String text, int maxTokens, int overlapSentences) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int budget = Math.max(1, maxTokens);
        int overlap = Math.max(0, overlapSentences);
        List<Chunk> sentences = sentences(text);
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < sentences.size()) {
            int end = start;
            int tokens = 0;
            while (end < sentences.size()) {
                int sentenceTokens = estimateTokens(sentences.get(end).text());
                if (end > start && tokens + sentenceTokens > budget) {
                    break;
                }
                tokens += sentenceTokens;
                end++;
                if (sentenceTokens > budget) {
                    break;
                }
            }
            if (end == start) {
                end = start + 1;
            }
            int chunkStart = sentences.get(start).charStart();
            int chunkEnd = sentences.get(end - 1).charEnd();
            chunks.add(new Chunk(text.substring(chunkStart, chunkEnd), chunkStart, chunkEnd));
            if (end >= sentences.size()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    public static List<Chunk> sentences(String text) {
        List<Chunk> sentences = new ArrayList<>();
        Matcher matcher = BOUNDARY.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            addSentence(sentences, text, cursor, matcher.start());
            cursor = matcher.end();
        }
        addSentence(sentences, text, cursor, text.length());
        return sentences;
    }

    public static int estimateTokens(String value) {
        return Math.max(1, (int) Math.ceil(value.length() / 4.0));
    }

    private static void addSentence(List<Chunk> sentences, String text, int start, int end) {
        if (end > start && !text.substring(start, end).isBlank()) {
            sentences.add(new Chunk(text.substring(start, end), start, end));
        }
    }
}

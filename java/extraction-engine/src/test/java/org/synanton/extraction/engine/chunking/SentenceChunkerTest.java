package org.synanton.extraction.engine.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SentenceChunkerTest {

    @Test
    void keepsWholeSentencesAndOrderWhenTheyFit() {
        String text = "First sentence. Second sentence! Third sentence?";
        List<SentenceChunker.Chunk> chunks = SentenceChunker.chunk(text, 100, 1);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).isEqualTo(text);
        assertThat(chunks.getFirst().charStart()).isZero();
        assertThat(chunks.getFirst().charEnd()).isEqualTo(text.length());
    }

    @Test
    void splitsAtBoundariesAndOverlapsOneSentence() {
        String text = "Alpha sentence. Beta sentence. Gamma sentence. Delta sentence.";
        List<SentenceChunker.Chunk> chunks = SentenceChunker.chunk(text, 8, 1);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().trim()).endsWith("."));
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).charStart()).isGreaterThan(chunks.get(i - 1).charStart());
        }
        String firstSentenceOfSecond = SentenceChunker.sentences(chunks.get(1).text()).getFirst().text().trim();
        assertThat(chunks.getFirst().text()).contains(firstSentenceOfSecond);
    }

    @Test
    void keepsAnOversizeSentenceWhole() {
        String longSentence = "A".repeat(200) + ".";
        List<SentenceChunker.Chunk> chunks = SentenceChunker.chunk(longSentence + " Tail.", 5, 0);
        assertThat(chunks.getFirst().text()).isEqualTo(longSentence);
    }

    @Test
    void blankLineIsABoundary() {
        String text = "Heading one\n\nBody sentence.";
        List<SentenceChunker.Chunk> sentences = SentenceChunker.sentences(text);
        assertThat(sentences).hasSize(2);
    }

    @Test
    void offsetsPointAtTheChunkText() {
        String text = "One. Two. Three. Four.";
        for (SentenceChunker.Chunk chunk : SentenceChunker.chunk(text, 3, 1)) {
            assertThat(text.substring(chunk.charStart(), chunk.charEnd())).isEqualTo(chunk.text());
        }
    }
}

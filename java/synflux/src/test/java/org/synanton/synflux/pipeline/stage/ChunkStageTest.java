package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.Test;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkStageTest {

    private static ParsedDocument parsedDoc(String text) {
        var ref = new ContentRef("file", "file:///test.txt", "text/plain", text.length(), Instant.now());
        var acquired = new AcquiredDocument(ref, text.getBytes(), "sha", "text/plain", "file:///test.txt", UUID.randomUUID());
        return new ParsedDocument(acquired, text, Map.of());
    }

    private static StageContext ctx() {
        return new StageContext("demo", UUID.randomUUID().toString(), null);
    }

    @Test
    void singleChunkWhenTextBelowTarget() {
        var stage = new ChunkStage(400, 50);
        var doc = parsedDoc("hello world this is a test");
        var result = stage.apply(doc, ctx());
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).ordinal()).isEqualTo(0);
        assertThat(result.chunks().get(0).text()).contains("hello");
    }

    @Test
    void multipleChunksWithOverlap() {
        var stage = new ChunkStage(5, 2);
        String text = String.join(" ", java.util.Collections.nCopies(12, "word"));
        var doc = parsedDoc(text);
        var result = stage.apply(doc, ctx());
        assertThat(result.chunks().size()).isGreaterThan(1);
        // Overlap: second chunk starts at position 3
        String first = result.chunks().get(0).text();
        String second = result.chunks().get(1).text();
        // They share the last 2 words of the first chunk
        String[] firstWords = first.split("\\s+");
        String[] secondWords = second.split("\\s+");
        assertThat(firstWords[firstWords.length - 1]).isEqualTo(secondWords[1]);
    }

    @Test
    void emptyTextProducesNoChunks() {
        var stage = new ChunkStage(400, 50);
        var doc = parsedDoc("   ");
        var result = stage.apply(doc, ctx());
        assertThat(result.chunks()).isEmpty();
    }

    @Test
    void sha256FieldPopulated() {
        var stage = new ChunkStage(400, 50);
        var doc = parsedDoc("some content here");
        var result = stage.apply(doc, ctx());
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().get(0).sha256()).hasSize(64);
    }
}

package org.synanton.bench.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 028a.10 acceptance: manifest paths exist on disk with matching shas
 * (round-trip — the consumer fixture checks field presence; this checks the
 * files the fields point at). Stable tree emitters reference:
 * {@code <outdir>/{documents,chunks,golden-queries}.jsonl + manifest.json}.
 */
class CorpusEmissionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void manifestPathsExistWithMatchingShas() throws Exception {
        Path dir = Files.createTempDirectory("corpus-emit");
        try {
            CorpusIo.Corpus corpus = CorpusIo.buildSkeletons();
            List<String> shas = CorpusIo.emitAll(corpus, dir);
            Files.writeString(
                    dir.resolve("manifest.json"),
                    CorpusManifest.emit(corpus, shas, CorpusEmit.FILE_NAMES).toPrettyString());
            JsonNode manifest = MAPPER.readTree(dir.resolve("manifest.json").toFile());
            JsonNode files = manifest.get("files");
            assertThat(files.size()).isEqualTo(3);
            var names = files.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                Path p = dir.resolve(files.get(name).get("path").asText());
                assertThat(Files.exists(p)).as("manifest path exists: " + name).isTrue();
                assertThat(CorpusIo.sha256File(p))
                        .as("manifest sha matches disk for " + name)
                        .isEqualTo(files.get(name).get("sha256").asText());
            }
            assertThat(manifest.get("corpus_version").asText())
                    .isEqualTo(CorpusManifest.CORPUS_VERSION);
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.delete(p);
                                    } catch (Exception e) {
                                        System.err.println("WARN: emission temp cleanup: " + e.getMessage());
                                    }
                                });
            }
        }
    }
}

package org.synanton.synflux.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticFixtureValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void goldManifestMatchesP1StructuralTextAndEvidence() throws Exception {
        Path root = repositoryRoot();
        Path fixture = root.resolve("evaluation/it-books/synthetic-ingestion-v1");
        JsonNode gold = mapper.readTree(fixture.resolve("gold/manifest.json").toFile());
        byte[] source = Files.readAllBytes(root.resolve(gold.path("fixture").path("sourcePath").asText()));
        assertThat(sha256(source)).isEqualTo(gold.path("fixture").path("sourceSha256").asText());

        DocumentStructure structure = DocumentStructureService.defaults().process(new SourceDocument(
            "synthetic", gold.path("fixture").path("documentId").asText(),
            gold.path("fixture").path("fixtureVersion").asText(),
            gold.path("fixture").path("documentId").asText(), "text/markdown", source));

        Map<String, DocumentStructure.Chunk> chunks = new HashMap<>();
        for (DocumentStructure.Chunk chunk : structure.chunks()) chunks.put(chunk.sectionPath(), chunk);
        for (JsonNode chunk : gold.path("chunks")) {
            DocumentStructure.Chunk actual = chunks.get(chunk.path("sectionPath").asText());
            assertThat(actual).as(chunk.path("sectionPath").asText()).isNotNull();
            assertThat(actual.documentStartOffset()).isEqualTo(chunk.path("documentStartOffset").asInt());
            assertThat(actual.documentEndOffset()).isEqualTo(chunk.path("documentEndOffset").asInt());
        }

        Map<String, DocumentStructure.Element> elements = new HashMap<>();
        for (DocumentStructure.Element element : structure.elements()) elements.put(element.elementId(), element);
        for (JsonNode mention : gold.path("mentions")) {
            String surface = mention.path("surface").asText();
            int start = mention.path("span").path("start").asInt();
            int end = mention.path("span").path("end").asInt();
            assertThat(structure.text().substring(start, end)).as(mention.path("id").asText()).isEqualTo(surface);
            DocumentStructure.Element element = elements.get(mention.path("evidence").path("elementId").asText());
            assertThat(element).as(mention.path("id").asText()).isNotNull();
            assertThat(element.documentStartOffset()).isLessThanOrEqualTo(start);
            assertThat(element.documentEndOffset()).isGreaterThanOrEqualTo(end);
            assertThat(element.text()).contains(surface);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))
                && Files.exists(current.resolve("evaluation/it-books/synthetic-ingestion-v1/gold/manifest.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

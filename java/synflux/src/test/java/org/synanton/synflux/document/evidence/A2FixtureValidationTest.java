package org.synanton.synflux.document.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.synanton.synflux.document.DocumentStructure;
import org.synanton.synflux.document.DocumentStructureService;
import org.synanton.synflux.document.SourceDocument;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class A2FixtureValidationTest {

    private static final Set<String> EXTRACTION_TYPES = Set.of(
        "Book", "Author", "Chapter", "Section", "Algorithm", "DataStructure", "Concept",
        "Database", "DistributedSystem", "Topic", "Definition");
    private static final Set<String> PREDICATES = Set.of(
        "AUTHORED_BY", "HAS_CHAPTER", "HAS_SECTION", "COVERS_TOPIC", "DEFINES", "USES",
        "DEPENDS_ON", "PART_OF");
    private static final Map<String, TypeRule> RULES = Map.of(
        "AUTHORED_BY", new TypeRule(Set.of("Book"), Set.of("Author")),
        "HAS_CHAPTER", new TypeRule(Set.of("Book"), Set.of("Chapter")),
        "HAS_SECTION", new TypeRule(Set.of("Chapter"), Set.of("Section")),
        "COVERS_TOPIC", new TypeRule(Set.of("Book", "Chapter", "Section"), Set.of("Topic")),
        "DEFINES", new TypeRule(Set.of("Chapter", "Section"), Set.of("Concept", "Definition")),
        "USES", new TypeRule(
            Set.of("Chapter", "Section", "Algorithm", "DataStructure", "Concept", "Database", "DistributedSystem"),
            Set.of("Chapter", "Section", "Algorithm", "DataStructure", "Concept", "Database", "DistributedSystem")),
        "DEPENDS_ON", new TypeRule(
            Set.of("Algorithm", "DataStructure", "Concept", "Database", "DistributedSystem"),
            Set.of("Algorithm", "DataStructure", "Concept", "Database", "DistributedSystem")),
        "PART_OF", new TypeRule(
            Set.of("Chapter", "Section", "Topic"), Set.of("Book", "Chapter", "Section"))
    );

    private record TypeRule(Set<String> subjects, Set<String> objects) {
        boolean accepts(String subject, String object) {
            return subjects.contains(subject) && objects.contains(object);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesA2AnnotationsAgainstP1StructureAndSourcePins() throws Exception {
        Path root = repositoryRoot();
        Path artifact = root.resolve("evaluation/it-books/a2-v1");
        JsonNode manifest = mapper.readTree(artifact.resolve("manifest.json").toFile());
        JsonNode annotations = mapper.readTree(artifact.resolve("annotations.json").toFile());
        assertThat(manifest.path("fixtureVersion").asText()).isEqualTo("it-books-a2-v1");
        assertThat(annotations.path("fixtureVersion").asText()).isEqualTo("it-books-a2-v1");

        Map<String, DocumentStructure> structures = new HashMap<>();
        Map<String, JsonNode> sourceById = new HashMap<>();
        for (JsonNode source : manifest.path("sources")) {
            String sourceId = source.path("sourceId").asText();
            sourceById.put(sourceId, source);
            Path original = root.resolve(source.path("originalPath").asText());
            Path excerpt = root.resolve(source.path("excerptPath").asText());
            assertThat(sha256(original)).isEqualTo(source.path("originalSha256").asText());
            assertThat(sha256(excerpt)).isEqualTo(source.path("excerptSha256").asText());
            assertThat(source.path("license").asText()).isNotBlank();
            assertThat(source.path("licenseUrl").asText()).startsWith("https://creativecommons.org/");
            assertThat(source.path("rightsBasis").asText()).isNotBlank();
            assertThat(Files.size(excerpt)).isLessThan(4096);

            structures.put(sourceId, DocumentStructureService.defaults().process(new SourceDocument(
                "a2-tenant", sourceId, "it-books-a2-v1", "a2-" + sourceId, "text/markdown",
                Files.readAllBytes(excerpt))));
        }

        assertThat(structures.get("think-python-2e").text())
            .as("non-BMP UTF-16 conformance probe")
            .contains("😀");

        Map<String, JsonNode> mentions = new HashMap<>();
        for (JsonNode mention : annotations.path("mentions")) {
            String sourceId = mention.path("sourceId").asText();
            DocumentStructure structure = structures.get(sourceId);
            assertThat(structure).as("source " + sourceId).isNotNull();
            assertThat(EXTRACTION_TYPES).contains(mention.path("type").asText());
            assertThat(mention.path("space").asText()).isEqualTo("STRUCTURAL_DOCUMENT");
            DocumentStructure.Element element = element(structure, mention.path("elementId").asText());
            assertThat(element.chunkId()).isEqualTo(mention.path("chunkId").asText());
            String surface = mention.path("surface").asText();
            int start = mention.path("documentStartOffset").asInt();
            int end = mention.path("documentEndOffset").asInt();
            assertThat(structure.text().substring(start, end)).isEqualTo(surface);
            assertThat(element.chunkStartOffset() + (start - element.documentStartOffset()))
                .isEqualTo(mention.path("chunkStartOffset").asInt());
            assertThat(element.chunkStartOffset() + (end - element.documentStartOffset()))
                .isEqualTo(mention.path("chunkEndOffset").asInt());
            assertThat(mention.path("geometryStatus").asText()).isEqualTo("NOT_APPLICABLE");
            assertThat(mention.path("pageNumber").isNull()).isTrue();
            assertThat(mention.path("boundingBox").isNull()).isTrue();
            assertThat(mention.path("sourcePage").asInt()).isPositive();
            assertThat(surface.length()).isLessThan(200);
            mentions.put(mention.path("id").asText(), mention);
        }

        Map<String, List<JsonNode>> byPredicate = new HashMap<>();
        int judgedNegative = 0;
        int unjudged = 0;
        for (JsonNode relation : annotations.path("relations")) {
            String predicate = relation.path("predicate").asText();
            assertThat(PREDICATES).contains(predicate);
            JsonNode subject = mentions.get(relation.path("subjectMentionId").asText());
            JsonNode object = mentions.get(relation.path("objectMentionId").asText());
            assertThat(subject).as("subject " + relation.path("id").asText()).isNotNull();
            assertThat(object).as("object " + relation.path("id").asText()).isNotNull();
            String judgment = relation.path("judgment").asText();
            assertThat(Set.of("ACCEPTED", "REJECTED", "UNJUDGED")).contains(judgment);
            boolean negative = relation.path("judgedNegative").asBoolean();
            if ("REJECTED".equals(judgment)) {
                assertThat(negative).isTrue();
                judgedNegative++;
            } else {
                assertThat(negative).isFalse();
            }
            if ("UNJUDGED".equals(judgment)) {
                unjudged++;
            }
            if (!"UNJUDGED".equals(judgment)) {
                boolean valid = RULES.get(predicate).accepts(
                    subject.path("type").asText(), object.path("type").asText());
                assertThat(valid).as(relation.path("id").asText())
                    .isEqualTo("ACCEPTED".equals(judgment));
            }
            byPredicate.computeIfAbsent(predicate, ignored -> new ArrayList<>()).add(relation);
        }

        assertThat(byPredicate.keySet()).containsExactlyInAnyOrderElementsOf(PREDICATES);
        for (String predicate : PREDICATES) {
            assertThat(byPredicate.get(predicate).stream()
                .anyMatch(r -> "ACCEPTED".equals(r.path("judgment").asText())))
                .as(predicate + " accepted example").isTrue();
            assertThat(byPredicate.get(predicate).stream()
                .anyMatch(r -> "REJECTED".equals(r.path("judgment").asText())))
                .as(predicate + " rejected example").isTrue();
        }
        assertThat(judgedNegative).isPositive();
        assertThat(unjudged).isPositive();

        assertThat(manifest.path("compiler").path("releaseDigest").asText()).hasSize(64);
        assertThat(manifest.path("compiler").path("specDigest").asText()).hasSize(64);
        assertThat(manifest.path("compiler").path("templateDigest").asText()).hasSize(64);
        assertThat(manifest.path("evidenceContract").asText()).isEqualTo("p3a-evidence-binding-v1");
        assertThat(Files.readString(artifact.resolve("SHA256SUMS")))
            .contains("manifest.json", "annotations.json", "sources/");
    }

    private static DocumentStructure.Element element(DocumentStructure structure, String id) {
        return structure.elements().stream()
            .filter(e -> e.elementId().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("element not found: " + id));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))
                && Files.exists(current.resolve("evaluation/it-books/a2-v1/manifest.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}

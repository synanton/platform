package org.synanton.extraction.engine.contract;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.llm.LlmExtractionAdapter;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.LlmClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5 independent validation boundary.
 *
 * <p>Expected domains/ranges are transcribed from #894 (A1), not from the P0
 * compiler output. If the compiled contract and the A1 intent disagree, this
 * test must fail rather than accept the artifact as its own authority.</p>
 */
class ItBooksA5ValidationTest {

    private static final Set<String> A1_ENTITY_TYPES = Set.of(
        "Book", "Author", "Chapter", "Section", "Algorithm", "DataStructure",
        "Concept", "Database", "DistributedSystem", "Topic", "Definition");

    private static final Map<String, RelationSpec> A1_RELATIONS = expectedRelations();

    @Test
    void compiledContractMatchesTheIndependentA1Matrix() throws Exception {
        CompiledExtractionContract contract = loadContract();
        assertThat(contract.entityIds()).containsExactlyInAnyOrderElementsOf(A1_ENTITY_TYPES);
        assertThat(contract.predicateIds()).containsExactlyInAnyOrderElementsOf(A1_RELATIONS.keySet());
        assertThat(contract.releaseId()).isEqualTo("47fe38f8-9f72-481b-826a-6c5b12a6027a");
        assertThat(contract.releaseDigest())
            .isEqualTo("3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c");

        for (CompiledExtractionContract.RelationType relation : contract.relations()) {
            RelationSpec expected = A1_RELATIONS.get(relation.id());
            assertThat(expected).as("unexpected predicate %s", relation.id()).isNotNull();
            assertThat(relation.subjects()).as("subjects for %s", relation.id())
                .containsExactlyInAnyOrderElementsOf(expected.subjects());
            assertThat(relation.objects()).as("objects for %s", relation.id())
                .containsExactlyInAnyOrderElementsOf(expected.objects());
            assertThat(expected.subjects()).isSubsetOf(A1_ENTITY_TYPES);
            assertThat(expected.objects()).isSubsetOf(A1_ENTITY_TYPES);
        }
    }

    @Test
    void everyA1ValidExampleIsAccepted() throws Exception {
        for (CandidateCase candidate : validCases()) {
            ExtractionResult result = extract(candidate.subject(), candidate.predicate(), candidate.object());
            assertThat(result.quarantined()).as("quarantine for %s", candidate).isEmpty();
            assertThat(result.relations()).as("accepted relation for %s", candidate).hasSize(1);
            assertThat(result.relations().getFirst().predicate()).isEqualTo(candidate.predicate());
        }
    }

    @Test
    void everyA1InvalidExampleIsQuarantinedAsDomainRange() throws Exception {
        for (CandidateCase candidate : invalidCases()) {
            ExtractionResult result = extract(candidate.subject(), candidate.predicate(), candidate.object());
            assertThat(result.relations()).as("relation must not be accepted: %s", candidate).isEmpty();
            assertThat(result.quarantined()).anySatisfy(line -> {
                assertThat(line.reasonCode()).isEqualTo("DOMAIN_RANGE");
                assertThat(line.category()).isEqualTo("SEMANTIC");
            });
        }
    }

    @Test
    void unknownPredicatesAreNeverAccepted() throws Exception {
        ExtractionResult result = extract("Book", "HAS_SUPPLIER", "Author");
        assertThat(result.relations()).isEmpty();
        assertThat(result.quarantined()).anySatisfy(line ->
            assertThat(line.reasonCode()).isEqualTo("UNKNOWN_PREDICATE"));
    }

    private static ExtractionResult extract(String subjectType, String predicate, String objectType)
            throws Exception {
        CompiledExtractionContract contract = loadContract();
        String body = line("entity", "Subject", subjectType, null, null)
            + line("entity", "Object", objectType, null, null)
            + line("relation", null, null, predicate, "Subject Object");
        LlmClient client = new LlmClient() {
            @Override public CompletionResponse complete(CompletionRequest request) {
                return new CompletionResponse(body, 10, 10, "stop", "a5-req", "a5-exec");
            }
            @Override public EmbedResponse embed(EmbedRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        LlmExtractionAdapter adapter = new LlmExtractionAdapter(
            client, "a5-test-model", 512, null, "extraction-v2", contract);
        return adapter.extract(new TextSegment("a5-chunk", "Subject Object", 0),
            new ExtractionContext("it-books-a5", "a5-doc", A1_ENTITY_TYPES, "a5-version"));
    }

    private static String line(String type, String surface, String proposedType,
                               String predicate, String evidenceQuote) {
        if ("entity".equals(type)) {
            return "{\"type\":\"entity\",\"surfaceForm\":\"" + surface
                + "\",\"proposedType\":\"" + proposedType
                + "\",\"charStart\":0,\"charEnd\":7}\n";
        }
        return "{\"type\":\"relation\",\"subjectForm\":\"Subject\",\"predicate\":\""
            + predicate + "\",\"objectForm\":\"Object\",\"evidenceQuote\":\""
            + evidenceQuote + "\"}\n";
    }

    private static List<CandidateCase> validCases() {
        return List.of(
            new CandidateCase("Book", "AUTHORED_BY", "Author"),
            new CandidateCase("Book", "HAS_CHAPTER", "Chapter"),
            new CandidateCase("Chapter", "HAS_SECTION", "Section"),
            new CandidateCase("Book", "COVERS_TOPIC", "Topic"),
            new CandidateCase("Chapter", "DEFINES", "Concept"),
            new CandidateCase("Algorithm", "USES", "DataStructure"),
            new CandidateCase("Database", "DEPENDS_ON", "DistributedSystem"),
            new CandidateCase("Chapter", "PART_OF", "Book"));
    }

    private static List<CandidateCase> invalidCases() {
        return List.of(
            new CandidateCase("Chapter", "AUTHORED_BY", "Author"),
            new CandidateCase("Book", "HAS_CHAPTER", "Section"),
            new CandidateCase("Book", "HAS_SECTION", "Section"),
            new CandidateCase("Author", "COVERS_TOPIC", "Topic"),
            new CandidateCase("Book", "COVERS_TOPIC", "Concept"),
            new CandidateCase("Book", "DEFINES", "Concept"),
            new CandidateCase("Book", "USES", "Algorithm"),
            new CandidateCase("Book", "DEPENDS_ON", "Database"),
            new CandidateCase("Database", "DEPENDS_ON", "Book"),
            new CandidateCase("Book", "PART_OF", "Chapter"));
    }

    private static Map<String, RelationSpec> expectedRelations() {
        Map<String, RelationSpec> expected = new LinkedHashMap<>();
        expected.put("AUTHORED_BY", new RelationSpec(Set.of("Book"), Set.of("Author")));
        expected.put("HAS_CHAPTER", new RelationSpec(Set.of("Book"), Set.of("Chapter")));
        expected.put("HAS_SECTION", new RelationSpec(Set.of("Chapter"), Set.of("Section")));
        expected.put("COVERS_TOPIC", new RelationSpec(
            Set.of("Book", "Chapter", "Section"), Set.of("Topic")));
        expected.put("DEFINES", new RelationSpec(
            Set.of("Chapter", "Section"), Set.of("Concept", "Definition")));
        expected.put("USES", new RelationSpec(
            Set.of("Chapter", "Section", "Algorithm", "DataStructure", "Database", "DistributedSystem"),
            Set.of("Algorithm", "Concept", "DataStructure", "Database", "DistributedSystem")));
        expected.put("DEPENDS_ON", new RelationSpec(
            Set.of("Algorithm", "Concept", "DataStructure", "Database", "DistributedSystem"),
            Set.of("Algorithm", "Concept", "DataStructure", "Database", "DistributedSystem")));
        expected.put("PART_OF", new RelationSpec(
            Set.of("Chapter", "Section", "Topic"), Set.of("Book", "Chapter", "Section")));
        return Map.copyOf(expected);
    }

    private static CompiledExtractionContract loadContract() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("repo root not found");
        Path p0 = root.resolve("docs/implementation/it-books/p0");
        return new P0ContractLoader().load(p0.resolve("it-books.spec.json"),
            p0.resolve("it-books.template.json"), p0.resolve("it-books.report.json"));
    }

    private record RelationSpec(Set<String> subjects, Set<String> objects) {}
    private record CandidateCase(String subject, String predicate, String object) {}
}

package org.synanton.extraction.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyCompilerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compilesPublishedItBooksReleaseDeterministically() throws Exception {
        JsonNode active = resource("it-books-v1-active.json");
        JsonNode ir = resource("it-books-v1-ir.json");
        ReleaseIdentity release = new ReleaseIdentity(
            active.get("tenantId").asText(), active.get("ontologyId").asText(),
            active.get("activeVersion").asText(), active.get("releaseId").asText(),
            active.get("releaseDigest").asText());
        OntologyCompiler.CompileRequest request = new OntologyCompiler.CompileRequest(
            release, ir, "p0-1", "it-books-v1-default", "1", "sha256:test-image");

        OntologyCompiler.CompileResult first = new OntologyCompiler().compile(request);
        OntologyCompiler.CompileResult second = new OntologyCompiler().compile(request);

        assertThat(first.specDigest()).isEqualTo(second.specDigest())
            .isEqualTo("8834d45a276792babbdf59ff6da43bf11ba5a8bdd43ca9755cfb159559694455");
        assertThat(first.templateDigest()).isEqualTo(second.templateDigest())
            .isEqualTo("333a900292eebabda75b4ed67ec909ecc5db450702e05ae23d108ed2cf2fb37b");
        assertThat(first.compilationId()).isEqualTo(second.compilationId())
            .isEqualTo("cea21d1a4cebf9e45e2e6ebba9ae4c3899dfc336b2b098a959db6dd5a06f7e0b");

        JsonNode report = first.report();
        assertThat(report.path("constructs").path("classesTotal").asInt()).isEqualTo(20);
        assertThat(report.path("constructs").path("classesSupport").asInt()).isEqualTo(9);
        assertThat(report.path("constructs").path("classesExtraction").asInt()).isEqualTo(11);
        assertThat(report.path("constructs").path("classesRepresentable").asInt()).isEqualTo(11);
        assertThat(report.path("constructs").path("relationsTotal").asInt()).isEqualTo(8);
        assertThat(report.path("constructs").path("relationsRepresentable").asInt()).isEqualTo(3);
        assertThat(report.path("constructs").path("relationsAdapted").asInt()).isEqualTo(5);
        assertThat(report.path("constructs").path("relationsUnsupported").asInt()).isZero();
        assertThat(report.path("unsupported")).isEmpty();
        assertThat(report.path("coverage").path("classes")).hasSize(20);
        assertThat(report.path("coverage").path("relations")).hasSize(8);
        assertThat(report.path("provenance").path("releaseDigest").asText())
            .isEqualTo(release.releaseDigest());
        assertThat(report.path("provenance").path("llmUsed").asBoolean()).isFalse();

        assertThat(first.spec().path("entities")).hasSize(11);
        assertThat(first.template().path("entityTargets")).hasSize(11);
        assertThat(first.template().path("relationTargets")).hasSize(8);

        JsonNode covers = relation(first, "COVERS_TOPIC");
        assertThat(covers.path("status").asText()).isEqualTo("ADAPTED");
        assertThat(strings(covers.path("subjects"))).containsExactly("Book", "Chapter", "Section");
        assertThat(strings(covers.path("objects"))).containsExactly("Topic");

        JsonNode authored = relation(first, "AUTHORED_BY");
        assertThat(authored.path("status").asText()).isEqualTo("REPRESENTABLE");
        assertThat(strings(authored.path("subjects"))).containsExactly("Book");
        assertThat(strings(authored.path("objects"))).containsExactly("Author");
    }

    @Test
    void reportsUnsupportedConstructsInsteadOfSilentlyDroppingThem() throws Exception {
        JsonNode ir = resource("it-books-v1-ir.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) ir.path("classes").get(9)).withArray("properties")
            .addObject().put("name", "raw").put("datatype", "xsd:binary");
        ReleaseIdentity release = new ReleaseIdentity("demo", "it-books", "it-books-v1",
            "47fe38f8-9f72-481b-826a-6c5b12a6027a",
            "3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c");

        OntologyCompiler.CompileResult result = new OntologyCompiler().compile(
            new OntologyCompiler.CompileRequest(release, ir, "p0-1", "default", "1", ""));

        assertThat(result.report().path("constructs").path("classesUnsupported").asInt()).isEqualTo(1);
        assertThat(result.report().path("unsupported").get(0).path("kind").asText()).isEqualTo("class");
        assertThat(result.report().path("unsupported").get(0).path("reason").asText())
            .contains("xsd:binary");
    }

    private JsonNode relation(OntologyCompiler.CompileResult result, String id) {
        for (JsonNode node : result.template().path("relationTargets")) {
            if (id.equals(node.path("predicate").asText())) {
                return node;
            }
        }
        throw new AssertionError("missing relation " + id);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private JsonNode resource(String name) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as(name).isNotNull();
            return mapper.readTree(input);
        }
    }
}

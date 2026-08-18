package org.synanton.relix.loader;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class Pass2AnalysisParserTest {

    private final Pass2AnalysisParser parser = new Pass2AnalysisParser();
    private final UUID refId = UUID.randomUUID();

    @Test
    void parsesValidPass2Json() {
        String json = """
            {
              "typed_entities": [
                {"label": "Acme Corp", "type": "Organization", "confidence": 0.9, "chunk_ordinals": [3, 7]},
                {"label": "Alice", "type": "Person", "confidence": 0.85, "chunk_ordinals": [3]}
              ],
              "relations": [
                {"from": "Alice", "to": "Acme Corp", "verb": "works_at", "confidence": 0.8, "chunk_ordinals": [3]}
              ]
            }
            """;

        var result = parser.parse(refId, 3, json);
        assertThat(result.entities()).hasSize(2);
        assertThat(result.entities().get(0).label()).isEqualTo("Acme Corp");
        assertThat(result.entities().get(0).type()).isEqualTo("Organization");
        assertThat(result.entities().get(0).chunkOrdinals()).containsExactly(3, 7);
        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().get(0).verb()).isEqualTo("works_at");
    }

    @Test
    void toleratesMalformedJson() {
        var result = parser.parse(refId, 0, "this is not json{{{");
        assertThat(result.entities()).isEmpty();
        assertThat(result.relations()).isEmpty();
    }

    @Test
    void skipsEntitiesWithBlankLabel() {
        String json = """
            {"typed_entities": [{"label": "", "type": "Org", "confidence": 0.9}]}
            """;
        var result = parser.parse(refId, 0, json);
        assertThat(result.entities()).isEmpty();
    }

    @Test
    void handlesAlternativeFieldName() {
        // Some LLMs may return "entities" instead of "typed_entities"
        String json = """
            {
              "entities": [
                {"label": "Beta Inc", "type": "Organization", "confidence": 0.7, "chunk_ordinals": [1]}
              ]
            }
            """;
        var result = parser.parse(refId, 1, json);
        assertThat(result.entities()).hasSize(1);
        assertThat(result.entities().get(0).label()).isEqualTo("Beta Inc");
    }

    @Test
    void skipsRelationsWithMissingFields() {
        String json = """
            {
              "typed_entities": [{"label": "X", "type": "T", "confidence": 0.5}],
              "relations": [{"from": "X", "confidence": 0.8}]
            }
            """;
        var result = parser.parse(refId, 0, json);
        assertThat(result.entities()).hasSize(1);
        assertThat(result.relations()).isEmpty();
    }
}

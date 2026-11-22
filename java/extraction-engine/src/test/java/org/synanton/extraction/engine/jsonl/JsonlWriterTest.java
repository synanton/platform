package org.synanton.extraction.engine.jsonl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.json.Json;

class JsonlWriterTest {

    @Test
    void writesOneCompleteObjectPerLine() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("type", "entity");
        first.put("surfaceForm", "Acme");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("type", "entity");
        second.put("surfaceForm", "Beta");

        String output = JsonlWriter.writeAll(List.of(first, second));

        assertThat(output.lines()).hasSize(2);
        assertThat(output).endsWith("\n");
        for (String line : output.lines().toList()) {
            assertThat(Json.parseObject(line).get("surfaceForm")).isIn("Acme", "Beta");
        }
    }

    @Test
    void escapesEmbeddedNewlinesAndUnicode() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "entity");
        record.put("surfaceForm", "line1\nline2 \u263a");

        String output = JsonlWriter.write(record);

        assertThat(output).doesNotContain("\n");
        assertThat(output).contains("\\n").contains("\u263a");
        assertThat(Json.parseObject(output).get("surfaceForm")).isEqualTo("line1\nline2 \u263a");
    }

    @Test
    void sortsKeysSoRepeatedWritesAreByteStable() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("z", 1);
        record.put("a", 2);
        record.put("m", List.of("x", true));
        record.put("n", null);

        assertThat(JsonlWriter.write(record)).isEqualTo(JsonlWriter.write(record));
        assertThat(JsonlWriter.write(record)).startsWith("{\"a\":2,\"m\":[\"x\",true],\"n\":null");
    }

    @Test
    void rejectsUnsupportedValues() {
        Map<String, Object> record = Map.of("bad", new Object());
        assertThatThrownBy(() -> JsonlWriter.write(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported JSON value");
    }

    @Test
    void roundTripsThroughTheLineParser() {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("type", "entity");
        entity.put("surfaceForm", "Acme");
        entity.put("proposedType", "Organization");
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("type", "relation");
        relation.put("subjectForm", "Acme");
        relation.put("predicate", "hasSupplier");
        relation.put("objectForm", "Beta");
        relation.put("evidenceQuote", "Acme has supplier Beta.");

        JsonlParseResult parsed = new JsonlLineParser().parse(JsonlWriter.writeAll(List.of(entity, relation)));

        assertThat(parsed.accepted()).hasSize(2);
        assertThat(parsed.rejected()).isEmpty();
    }
}

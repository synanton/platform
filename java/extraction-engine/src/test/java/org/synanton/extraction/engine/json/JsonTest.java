package org.synanton.extraction.engine.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesEscapesAndRawControlCharacters() {
        Map<String, Object> object = Json.parseObject(
                "{\"a\":\"line\nbreak\",\"b\":\"quote\\\"\",\"c\":\"\\u263a\",\"n\":3}");
        assertThat(object.get("a")).isEqualTo("line\nbreak");
        assertThat(object.get("b")).isEqualTo("quote\"");
        assertThat(object.get("c")).isEqualTo("\u263a");
        assertThat(object.get("n")).isEqualTo(3.0);
    }

    @Test
    void extractsAnObjectFromFencedModelOutput() {
        String text = "```json\n{\"preEntities\":[],\"relations\":[]}\n```";
        assertThat(Json.extractObject(text)).isEqualTo("{\"preEntities\":[],\"relations\":[]}");
    }

    @Test
    void quoteRoundTrips() {
        String value = "a \"quoted\" line\nwith\tcontrol";
        assertThat(Json.parseObject("{\"v\":" + Json.quote(value) + "}").get("v")).isEqualTo(value);
    }
}

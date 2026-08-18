package org.synanton.common.security.sanitizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.synanton.common.security.sanitizer.SanitizerTestKit;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizingStringDeserializerTest {

    private final HtmlSanitizerPolicyFactory factory =
            new HtmlSanitizerPolicyFactory(SanitizerProperties.platformDefault());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObjectMapper mapper = objectMapper();

    @Test
    void shouldStripScriptTagsFromOwaspPayloads() throws Exception {
        for (String payload : SanitizerTestKit.owaspEvasionPayloads()) {
            String json = mapper.writeValueAsString(new Envelope(payload));
            Envelope parsed = mapper.readValue(json, Envelope.class);
            assertThat(parsed.value() == null ? "" : parsed.value().toLowerCase())
                    .doesNotContain("<script")
                    .doesNotContain("onerror=");
        }
    }

    @Test
    void shouldSkipSanitisationWhenAllowHtmlIsPresent() throws Exception {
        ObjectMapper allowing = new ObjectMapper();
        SanitizingStringDeserializer deserializer =
                new SanitizingStringDeserializer(factory.policy(), registry, true, "html");
        SimpleModule module = new SimpleModule();
        module.addDeserializer(String.class, deserializer);
        allowing.registerModule(module);

        HtmlEnvelope parsed = allowing.readValue("{\"html\":\"<b>ok</b><script>x</script>\"}", HtmlEnvelope.class);
        assertThat(parsed.html()).isEqualTo("<b>ok</b><script>x</script>");
        assertThat(registry.counter("synapt_sanitization_skipped_total", "tenant", "unknown", "field", "html")
                .count()).isEqualTo(1.0);
    }

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new SanitizingModule(factory, registry));
        return objectMapper;
    }

    public record Envelope(String value) {}

    public record HtmlEnvelope(@AllowHtml String html) {}
}

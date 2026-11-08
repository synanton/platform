package org.synanton.synflux.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.llm.LlmExtractionAdapter;
import org.synanton.extraction.engine.regex.RegexExtractorAdapter;

class ExtractionEngineSelectionTest {

    private final SynfluxConfig config = new SynfluxConfig();

    @Test
    void defaultsToRegexWithoutConfiguration() {
        assertThat(config.extractionEngineAdapter(new ExtractionEngineProperties(null, null, null, null)))
                .isInstanceOf(RegexExtractorAdapter.class);
    }

    @Test
    void selectsTheLlmAdapterWhenRequested() {
        assertThat(config.extractionEngineAdapter(new ExtractionEngineProperties(
                "llm", "http://localhost:8000/v1", "qwen3-extraction", 30)))
                .isInstanceOf(LlmExtractionAdapter.class);
    }

    @Test
    void rejectsAnUnknownProvider() {
        assertThatThrownBy(() -> config.extractionEngineAdapter(
                new ExtractionEngineProperties("magic", null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("want regex|llm");
    }
}

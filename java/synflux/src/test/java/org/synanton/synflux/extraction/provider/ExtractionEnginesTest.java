package org.synanton.synflux.extraction.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.llm.LlmExtractionAdapter;
import org.synanton.synflux.config.ExtractionEngineProperties;

class ExtractionEnginesTest {

    private static ExtractionEngineProperties props(String captureDir, Boolean enabled) {
        return props(captureDir, enabled, null);
    }

    private static ExtractionEngineProperties props(String captureDir, Boolean enabled,
                                                    String environment) {
        return new ExtractionEngineProperties("llm", "http://localhost:8000/v1",
                "qwen3-extraction", 120, 2048, captureDir, enabled, environment);
    }

    @Test
    void aPathAloneDoesNotEnableCapture() {
        LlmExtractionAdapter adapter = (LlmExtractionAdapter) ExtractionEngines.create(
                ExtractionProvider.LLM, props("/tmp/capture", false));

        assertThat(adapter.captureDirectory()).isEmpty();
    }

    @Test
    void enabledCaptureRequiresAPath() {
        LlmExtractionAdapter adapter = (LlmExtractionAdapter) ExtractionEngines.create(
                ExtractionProvider.LLM, props("", true));

        assertThat(adapter.captureDirectory()).isEmpty();
    }

    @Test
    void enabledCaptureRefusesProduction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ExtractionEngines.create(
                        ExtractionProvider.LLM, props("/tmp/capture", true, "production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled in production");
    }

    @Test
    void enabledCaptureWithAPathIsExposed() {
        LlmExtractionAdapter adapter = (LlmExtractionAdapter) ExtractionEngines.create(
                ExtractionProvider.LLM, props("/tmp/capture", true));

        assertThat(adapter.captureDirectory()).contains(Path.of("/tmp/capture"));
    }
}

package org.synanton.synflux.extraction.provider;

import java.nio.file.Path;
import java.time.Duration;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.llm.LlmExtractionAdapter;
import org.synanton.extraction.engine.regex.RegexExtractorAdapter;
import org.synanton.synflux.config.ExtractionEngineProperties;

/** Builds adapters from the canonical provider vocabulary. */
public final class ExtractionEngines {
    private ExtractionEngines() {
    }

    private static Path capturePath(ExtractionEngineProperties properties) {
        if (!Boolean.TRUE.equals(properties.captureEnabled())) {
            return null;
        }
        return properties.captureDir() == null || properties.captureDir().isBlank()
                ? null : Path.of(properties.captureDir());
    }

    public static ExtractionEngineAdapter create(ExtractionProvider provider,
                                                 ExtractionEngineProperties properties) {
        return switch (provider) {
            case REGEX -> new RegexExtractorAdapter();
            case LLM -> new LlmExtractionAdapter(
                    properties.endpoint() == null || properties.endpoint().isBlank()
                            ? "http://localhost:8000/v1"
                            : properties.endpoint(),
                    properties.model() == null || properties.model().isBlank()
                            ? "qwen3-extraction"
                            : properties.model(),
                    Duration.ofSeconds(properties.timeoutSeconds() == null
                            ? 120
                            : Math.max(1, properties.timeoutSeconds())),
                    properties.maxTokens() == null ? 2048 : Math.max(1, properties.maxTokens()),
                    capturePath(properties));
        };
    }
}

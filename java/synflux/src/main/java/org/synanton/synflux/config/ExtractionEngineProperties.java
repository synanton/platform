package org.synanton.synflux.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAKG extraction engine selection. {@code provider=regex} (default) keeps
 * ingestion working without the GPU plane; {@code provider=llm} calls the
 * OpenAI-compatible vLLM extraction endpoint.
 *
 * <p>Diagnostic capture requires both a path and {@code captureEnabled=true}.
 * A path alone must not enable capture; see D5 capture controls.
 */
@ConfigurationProperties(prefix = "synflux.extraction-engine")
public record ExtractionEngineProperties(
        String provider,
        String endpoint,
        String model,
        Integer timeoutSeconds,
        Integer maxTokens,
        String captureDir,
        Boolean captureEnabled) {
}

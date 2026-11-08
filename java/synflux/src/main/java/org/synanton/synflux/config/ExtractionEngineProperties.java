package org.synanton.synflux.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAKG extraction engine selection. {@code provider=regex} (default) keeps
 * ingestion working without the GPU plane; {@code provider=llm} calls the
 * OpenAI-compatible vLLM extraction endpoint.
 */
@ConfigurationProperties(prefix = "synflux.extraction-engine")
public record ExtractionEngineProperties(
        String provider,
        String endpoint,
        String model,
        Integer timeoutSeconds) {}

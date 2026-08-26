package org.synanton.extraction.client;

import java.util.Map;

public record FallbackExtractionResult(
        String flatText,
        Map<String, String> metadata
) {}

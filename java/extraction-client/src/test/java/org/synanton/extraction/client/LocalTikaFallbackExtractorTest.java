package org.synanton.extraction.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTikaFallbackExtractorTest {

    private final LocalTikaFallbackExtractor extractor = new LocalTikaFallbackExtractor();

    @Test
    void shouldExtractFlatTextWithMetadata() {
        FallbackExtractionResult result = extractor.extract(
                "hello tika fallback".getBytes(), "file:///tmp/a.txt", true);
        assertThat(result.flatText()).contains("hello tika fallback");
        assertThat(result.metadata()).isNotNull();
    }

    @Test
    void shouldSkipMetadataWhenTextOnlyPolicy() {
        FallbackExtractionResult result = extractor.extract(
                "hello tika fallback".getBytes(), "file:///tmp/a.txt", false);
        assertThat(result.flatText()).contains("hello tika fallback");
        assertThat(result.metadata()).isEmpty();
    }
}

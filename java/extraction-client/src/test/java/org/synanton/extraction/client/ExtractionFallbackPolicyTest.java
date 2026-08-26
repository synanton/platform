package org.synanton.extraction.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionFallbackPolicyTest {

    @Test
    void shouldMapConfigValuesToPolicies() {
        assertThat(ExtractionFallbackPolicy.fromConfig("fail")).isEqualTo(ExtractionFallbackPolicy.STRUCTURED_REQUIRED);
        assertThat(ExtractionFallbackPolicy.fromConfig("local-tika"))
                .isEqualTo(ExtractionFallbackPolicy.FALLBACK_LOCAL_TIKA);
        assertThat(ExtractionFallbackPolicy.fromConfig("partial"))
                .isEqualTo(ExtractionFallbackPolicy.FAIL_OPEN_TEXT_ONLY);
        assertThat(ExtractionFallbackPolicy.fromConfig(null))
                .isEqualTo(ExtractionFallbackPolicy.FALLBACK_LOCAL_TIKA);
    }
}

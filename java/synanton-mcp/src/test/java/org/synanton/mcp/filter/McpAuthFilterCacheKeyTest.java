package org.synanton.mcp.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpAuthFilterCacheKeyTest {

    @Test
    void cacheKey_isDeterministicHexOfFixedLength() {
        String first = McpAuthFilter.cacheKey("Bearer abc123");
        String second = McpAuthFilter.cacheKey("Bearer abc123");
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
        assertThat(first).matches("[0-9a-f]+");
    }

    @Test
    void cacheKey_neverContainsTheRawBearer() {
        String bearer = "Bearer live-credential-value";
        assertThat(McpAuthFilter.cacheKey(bearer)).doesNotContain("live-credential-value");
    }

    @Test
    void cacheKey_distinctBearersGiveDistinctKeys() {
        assertThat(McpAuthFilter.cacheKey("Bearer one"))
                .isNotEqualTo(McpAuthFilter.cacheKey("Bearer two"));
    }
}

package org.synanton.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientConfigTest {

    @Test
    void defaultsAppliedWhenNullsAndZerosPassed() {
        LlmClientConfig cfg = new LlmClientConfig(null, null, 0, -1);

        assertThat(cfg.baseUrl()).isEqualTo("http://localhost:8000/v1");
        assertThat(cfg.model()).isEqualTo("default");
        assertThat(cfg.timeoutMs()).isEqualTo(30_000L);
        assertThat(cfg.maxRetries()).isEqualTo(3);
    }

    @Test
    void explicitValuesArePreserved() {
        LlmClientConfig cfg = new LlmClientConfig("http://api.example.com/v1", "llama3", 5_000, 1);

        assertThat(cfg.baseUrl()).isEqualTo("http://api.example.com/v1");
        assertThat(cfg.model()).isEqualTo("llama3");
        assertThat(cfg.timeoutMs()).isEqualTo(5_000L);
        assertThat(cfg.maxRetries()).isEqualTo(1);
    }

    @Test
    void zeroMaxRetriesIsAllowed() {
        // maxRetries < 0 defaults to 3; exactly 0 means "no retries"
        LlmClientConfig cfg = new LlmClientConfig("http://localhost/v1", "m", 1_000, 0);
        assertThat(cfg.maxRetries()).isZero();
    }
}

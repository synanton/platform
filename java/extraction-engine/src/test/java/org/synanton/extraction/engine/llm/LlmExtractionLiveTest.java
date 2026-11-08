package org.synanton.extraction.engine.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.TextSegment;

/**
 * Live E2E against the cluster vLLM endpoint. Skipped unless
 * {@code LLM_EXTRACTION_URL} is set (e.g. after
 * {@code kubectl -n gpu-plane port-forward deploy/vllm-extraction 8000:8000}).
 */
@EnabledIfEnvironmentVariable(named = "LLM_EXTRACTION_URL", matches = ".+")
class LlmExtractionLiveTest {

    @Test
    void extractsAgainstTheLiveVllmEndpoint() {
        String endpoint = System.getenv("LLM_EXTRACTION_URL");
        String model = System.getenv().getOrDefault("LLM_EXTRACTION_MODEL", "qwen3-extraction");
        ExtractionResult result = new LlmExtractionAdapter(endpoint, model, Duration.ofSeconds(180))
                .extract(new TextSegment("live",
                                "The Halcyon-7 auxiliary coolant loop must be pressure-tested every ninety days.",
                                0),
                        new ExtractionContext("test", "live", Set.of()));
        assertThat(result.preEntities()).isNotEmpty();
        assertThat(result.preEntities()).anyMatch(entity -> entity.surfaceForm().contains("Halcyon-7"));
    }
}

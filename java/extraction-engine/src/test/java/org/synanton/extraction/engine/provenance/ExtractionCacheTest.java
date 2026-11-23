package org.synanton.extraction.engine.provenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.PreEntity;

class ExtractionCacheTest {

    private static final UUID SOURCE_VERSION = UUID.randomUUID();
    private static final String PROMPT_DIGEST = "a".repeat(64);

    @Test
    void cacheIdentityIncludesPromptDigestAndExcludesGeneration() {
        ExtractionCacheKey first = key(PROMPT_DIGEST);
        ExtractionCacheKey same = key(PROMPT_DIGEST);
        ExtractionCacheKey changed = key("b".repeat(64));

        assertThat(first).isEqualTo(same);
        assertThat(first.cacheKey()).isEqualTo(same.cacheKey());
        assertThat(first.cacheKey()).doesNotContain("generation");
        assertThat(changed.cacheKey()).isNotEqualTo(first.cacheKey());
    }

    @Test
    void everyContractInputChangesCacheIdentity() {
        ExtractionCacheKey base = key(PROMPT_DIGEST);
        assertThat(ExtractionCacheKey.forRequest("other", SOURCE_VERSION, "segment text",
                PROMPT_DIGEST, "c".repeat(64), "qwen3-8b-fp8", "rev", 0.0, 0.0, "tok").cacheKey())
                .isNotEqualTo(base.cacheKey());
        assertThat(ExtractionCacheKey.forRequest("tenant", UUID.randomUUID(), "segment text",
                PROMPT_DIGEST, "c".repeat(64), "qwen3-8b-fp8", "rev", 0.0, 0.0, "tok").cacheKey())
                .isNotEqualTo(base.cacheKey());
        assertThat(ExtractionCacheKey.forRequest("tenant", SOURCE_VERSION, "different segment",
                PROMPT_DIGEST, "c".repeat(64), "qwen3-8b-fp8", "rev", 0.0, 0.0, "tok").cacheKey())
                .isNotEqualTo(base.cacheKey());
        assertThat(ExtractionCacheKey.forRequest("tenant", SOURCE_VERSION, "segment text",
                PROMPT_DIGEST, "d".repeat(64), "qwen3-8b-fp8", "rev", 0.0, 0.0, "tok").cacheKey())
                .isNotEqualTo(base.cacheKey());
        assertThat(ExtractionCacheKey.forRequest("tenant", SOURCE_VERSION, "segment text",
                PROMPT_DIGEST, "c".repeat(64), "other-model", "rev", 0.0, 0.0, "tok").cacheKey())
                .isNotEqualTo(base.cacheKey());
        assertThat(ExtractionCacheKey.forRequest("tenant", SOURCE_VERSION, "segment text",
                PROMPT_DIGEST, "c".repeat(64), "qwen3-8b-fp8", "other-rev", 0.0, 0.0, "tok").cacheKey())
                .isNotEqualTo(base.cacheKey());
        assertThat(ExtractionCacheKey.forRequest("tenant", SOURCE_VERSION, "segment text",
                PROMPT_DIGEST, "c".repeat(64), "qwen3-8b-fp8", "rev", 0.1, 0.0, "tok").cacheKey())
                .isNotEqualTo(base.cacheKey());
        assertThat(ExtractionCacheKey.forRequest("tenant", SOURCE_VERSION, "segment text",
                PROMPT_DIGEST, "c".repeat(64), "qwen3-8b-fp8", "rev", 0.0, 0.1, "tok").cacheKey())
                .isNotEqualTo(base.cacheKey());
        assertThat(ExtractionCacheKey.forRequest("tenant", SOURCE_VERSION, "segment text",
                PROMPT_DIGEST, "c".repeat(64), "qwen3-8b-fp8", "rev", 0.0, 0.0, "other-tokenizer").cacheKey())
                .isNotEqualTo(base.cacheKey());
    }

    @Test
    void identicalInputsHitWithoutCreatingGenerationsOrCallingTheModel() {
        ExtractionCache cache = new ExtractionCache();
        ExtractionCacheKey key = key(PROMPT_DIGEST);
        UUID originalGeneration = UUID.randomUUID();
        CachedExtraction cached = new CachedExtraction(originalGeneration,
                List.of(new PreEntity("m1", "Acme", "Organization", 0, 4, "Acme")),
                List.of());
        cache.put(key, cached);
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger generationCreations = new AtomicInteger();

        CachedExtraction hit = cache.get(key).orElseGet(() -> {
            modelCalls.incrementAndGet();
            generationCreations.incrementAndGet();
            return new CachedExtraction(UUID.randomUUID(), List.of(), List.of());
        });

        assertThat(hit.generationId()).isEqualTo(originalGeneration);
        assertThat(hit.preEntities()).containsExactlyElementsOf(cached.preEntities());
        assertThat(modelCalls).hasValue(0);
        assertThat(generationCreations).hasValue(0);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void changedPromptDigestMisses() {
        ExtractionCache cache = new ExtractionCache();
        cache.put(key(PROMPT_DIGEST), new CachedExtraction(UUID.randomUUID(), List.of(), List.of()));

        assertThat(cache.get(key("b".repeat(64)))).isEmpty();
    }

    private static ExtractionCacheKey key(String promptDigest) {
        return ExtractionCacheKey.forRequest("tenant", SOURCE_VERSION, "segment text",
                promptDigest, "c".repeat(64), "qwen3-8b-fp8", "rev", 0.0, 0.0, "tok");
    }
}

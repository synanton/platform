package org.synanton.extraction.engine.provenance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.LlmProvenance;

class PromptReplayPolicyTest {

    private static final String COMPILED = "a".repeat(64);
    private static final String RENDERED = "b".repeat(64);

    @Test
    void compiledAndRenderedMatchIsFullReplayAndCacheEligible() {
        PromptReplayPolicy.Decision decision = PromptReplayPolicy.decide(
                new LlmProvenance("v2", COMPILED, RENDERED, null, null),
                null, RENDERED, false);

        assertThat(decision.mode()).isEqualTo(PromptReplayPolicy.Mode.FULL);
        assertThat(decision.cacheEligible()).isTrue();
    }

    @Test
    void compiledAndRenderedMismatchIsRejected() {
        PromptReplayPolicy.Decision decision = PromptReplayPolicy.decide(
                new LlmProvenance("v2", COMPILED, RENDERED, null, null),
                null, "c".repeat(64), false);

        assertThat(decision.mode()).isEqualTo(PromptReplayPolicy.Mode.REJECTED);
        assertThat(decision.cacheEligible()).isFalse();
    }

    @Test
    void renderedOnlyIsVerificationOnlyAndNeverCacheEligible() {
        PromptReplayPolicy.Decision decision = PromptReplayPolicy.decide(
                new LlmProvenance("v2", null, RENDERED, null, null),
                null, RENDERED, true);

        assertThat(decision.mode()).isEqualTo(PromptReplayPolicy.Mode.VERIFICATION_ONLY);
        assertThat(decision.cacheEligible()).isFalse();
    }

    @Test
    void legacyNullRequiresExplicitMatchingVerification() {
        LlmProvenance legacy = new LlmProvenance("v1", null, null, null, null);

        assertThat(PromptReplayPolicy.decide(legacy, RENDERED, RENDERED, false).mode())
                .isEqualTo(PromptReplayPolicy.Mode.REJECTED);
        assertThat(PromptReplayPolicy.decide(legacy, RENDERED, RENDERED, true).mode())
                .isEqualTo(PromptReplayPolicy.Mode.VERIFICATION_ONLY);
        assertThat(PromptReplayPolicy.decide(legacy, RENDERED, "c".repeat(64), true).mode())
                .isEqualTo(PromptReplayPolicy.Mode.REJECTED);
    }

    @Test
    void compiledWithoutRenderedIsRejected() {
        PromptReplayPolicy.Decision decision = PromptReplayPolicy.decide(
                new LlmProvenance("v2", COMPILED, null, null, null),
                null, RENDERED, false);

        assertThat(decision.mode()).isEqualTo(PromptReplayPolicy.Mode.REJECTED);
    }
}

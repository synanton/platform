package org.synanton.extraction.engine.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromptDigestTest {

    @Test
    void matchesTheSharedContractVector() {
        assertThat(PromptDigest.digest("You extract JSONL.\nReturn one object per line."))
                .isEqualTo("5bce5985be0a04b98f3ea0977748db3205181028cf1498b27cbd7ecbc078c863");
    }

    @Test
    void verifyReturnsTheRenderedDigestWhenBytesMatch() {
        String prompt = "same bytes";
        assertThat(PromptDigest.verify(prompt, prompt)).isEqualTo(PromptDigest.digest(prompt));
    }

    @Test
    void oneByteMutationFailsClosed() {
        String compiled = "prompt";
        String rendered = "prompX";

        assertThatThrownBy(() -> PromptDigest.verify(compiled, rendered))
                .isInstanceOf(PromptDigestMismatchException.class)
                .hasMessageContaining("PROMPT_DIGEST_MISMATCH");
    }
}

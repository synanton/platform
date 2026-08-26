package org.synanton.synflux.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceUsageTest {

    @Test
    void shouldRoundTripJsonWithStages() {
        ResourceUsage usage = ResourceUsage.fromStages(List.of(
            new StageUsage("extraction", 12, 5_000_000L, null, 0, 42, 0, 0, 100, 0),
            new StageUsage("embed", 8, 2_000_000L, "bge-base", 120, 0, 30, 0, 0, 0)
        ));

        ResourceUsage restored = ResourceUsage.fromJson(usage.toJson());

        assertThat(restored).isEqualTo(usage);
        assertThat(restored.stages()).hasSize(2);
        assertThat(restored.wallMs()).isEqualTo(20);
        assertThat(restored.outputChars()).isEqualTo(42);
        assertThat(restored.modelInputTokens()).isEqualTo(30);
    }
}

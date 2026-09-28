package org.synanton.bench.emitter;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-query guard semantics: equivalence for lexical-filtered, continuity otherwise. */
class RetrievabilityGuardTest {

    private static final Map<String, String> TEXTS =
            Map.of("c1", "alpha beta", "c2", "beta gamma", "c3", "gamma delta");

    @Test
    void structuralEmptyPassesWhenNothingRetrievable() {
        RetrievabilityGuard.Verdict v =
                RetrievabilityGuard.check("q", "alpha zeta", List.of("c1", "c2"), List.of(),
                        TEXTS, true);
        assertThat(v.pass()).isTrue();
        assertThat(v.retrievable()).isZero();
    }

    @Test
    void populatedWithRetrievablePresentPasses() {
        RetrievabilityGuard.Verdict v =
                RetrievabilityGuard.check("q", "alpha beta", List.of("c1", "c2"), List.of("c1"),
                        TEXTS, true);
        assertThat(v.pass()).isTrue();
    }

    @Test
    void emptyWithRetrievablePresentFails() {
        RetrievabilityGuard.Verdict v =
                RetrievabilityGuard.check("q", "alpha beta", List.of("c1", "c2"), List.of(),
                        TEXTS, true);
        assertThat(v.pass()).isFalse();
        assertThat(v.detail()).contains("MISMATCH");
    }

    @Test
    void knnLegsRequireNonEmpty() {
        RetrievabilityGuard.Verdict v =
                RetrievabilityGuard.check("q", "", List.of("c1"), List.of(), TEXTS, false);
        assertThat(v.pass()).isFalse();
        assertThat(v.detail()).contains("defect, not structural");
    }
}

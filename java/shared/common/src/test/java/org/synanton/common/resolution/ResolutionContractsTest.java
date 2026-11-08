package org.synanton.common.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResolutionContractsTest {

    @Test
    void reviewRequiredIsTheOnlyNonTerminalOutcome() {
        assertThat(ResolutionOutcome.RESOLVED.terminal()).isTrue();
        assertThat(ResolutionOutcome.AMBIGUOUS.terminal()).isTrue();
        assertThat(ResolutionOutcome.UNRESOLVED.terminal()).isTrue();
        assertThat(ResolutionOutcome.REVIEW_REQUIRED.terminal()).isFalse();
    }

    @Test
    void completedMeansAllTerminalNotAllResolved() {
        assertThat(ResolutionLifecycle.derive(List.of(
                ResolutionOutcome.RESOLVED, ResolutionOutcome.AMBIGUOUS, ResolutionOutcome.UNRESOLVED)))
                .isEqualTo(ResolutionLifecycle.COMPLETED);
    }

    @Test
    void reviewRequiredMakesTheVersionPartial() {
        assertThat(ResolutionLifecycle.derive(List.of(
                ResolutionOutcome.RESOLVED, ResolutionOutcome.REVIEW_REQUIRED)))
                .isEqualTo(ResolutionLifecycle.PARTIAL);
    }

    @Test
    void emptyVersionIsComplete() {
        assertThat(ResolutionLifecycle.derive(List.of())).isEqualTo(ResolutionLifecycle.COMPLETED);
    }
}

package org.synanton.bench.convergence;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-POC-017 acceptance: pass / fail / edge cases over fixture runs.
 * Thresholds come from the real pinned config (0.90), not test doubles —
 * the test fails if the config drifts.
 */
class ConvergenceRunnerTest {

    private static RunOutput fixture(String name) throws Exception {
        try (InputStream in =
                ConvergenceRunnerTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture present: " + name).isNotNull();
            return RunOutput.parse(in, name);
        }
    }

    private static ConvergenceConfig realConfig() throws Exception {
        try (InputStream in =
                ConvergenceRunnerTest.class.getResourceAsStream("/fixtures/test-config.yaml")) {
            assertThat(in).as("test config present").isNotNull();
            return ConvergenceConfig.parse(in);
        }
    }

    @Test
    void passingCandidatePassesOverall() throws Exception {
        ConvergenceRunner.Report report =
                ConvergenceRunner.compare(
                        fixture("baseline-sample.json"), fixture("candidate-pass.json"), realConfig());
        assertThat(report.legs()).hasSize(3);
        assertThat(report.legs()).allSatisfy(v -> assertThat(v.pass()).isTrue());
        assertThat(report.overallPass()).isTrue();
    }

    @Test
    void lexicalDivergenceFailsOnlyItsLeg() throws Exception {
        ConvergenceRunner.Report report =
                ConvergenceRunner.compare(
                        fixture("baseline-sample.json"),
                        fixture("candidate-fail-lexical.json"),
                        realConfig());
        assertThat(report.overallPass()).isFalse();
        ConvergenceRunner.LegVerdict lex =
                report.legs().stream().filter(v -> v.mode().equals("lexical")).findFirst().orElseThrow();
        assertThat(lex.pass()).isFalse();
        assertThat(lex.value()).isEqualTo(0.5);
        assertThat(report.legs().stream().filter(v -> !v.mode().equals("lexical")))
                .allSatisfy(v -> assertThat(v.pass()).isTrue());
    }

    @Test
    void eligibilityBreakIsHardFailWithDefectWording() throws Exception {
        ConvergenceRunner.Report report =
                ConvergenceRunner.compare(
                        fixture("baseline-sample.json"),
                        fixture("candidate-fail-eligibility.json"),
                        realConfig());
        assertThat(report.overallPass()).isFalse();
        ConvergenceRunner.LegVerdict elig =
                report.legs().stream()
                        .filter(v -> v.filter().equals("eligibility"))
                        .findFirst()
                        .orElseThrow();
        assertThat(elig.pass()).isFalse();
        assertThat(elig.check()).isEqualTo("eligible-set-identity");
        assertThat(elig.detail()).contains("defect, not caveat");
    }

    @Test
    void emptyTopKOverlapsZeroButIdentityStillHolds() throws Exception {
        ConvergenceRunner.Report report =
                ConvergenceRunner.compare(
                        fixture("baseline-sample.json"),
                        fixture("candidate-empty-topk.json"),
                        realConfig());
        assertThat(report.overallPass()).isFalse();
        ConvergenceRunner.LegVerdict lex =
                report.legs().stream().filter(v -> v.mode().equals("lexical")).findFirst().orElseThrow();
        assertThat(lex.value()).isZero();
        // Eligible-set identity is about the candidate set, not the hits:
        // empty topK with identical sets still passes its leg.
        assertThat(report.legs().stream().filter(v -> v.filter().equals("eligibility")))
                .allSatisfy(v -> assertThat(v.pass()).isTrue());
    }

    @Test
    void allTiesPassOnSetOverlapRegardlessOfOrder() throws Exception {
        ConvergenceRunner.Report report =
                ConvergenceRunner.compare(
                        fixture("baseline-sample.json"),
                        fixture("candidate-all-ties.json"),
                        realConfig());
        assertThat(report.overallPass()).isTrue();
    }

    @Test
    void corpusMismatchFailsLoudly() throws Exception {
        RunOutput baseline = fixture("baseline-sample.json");
        RunOutput other =
                new RunOutput("x", "other-corpus", baseline.queries());
        assertThatThrownBy(() -> ConvergenceRunner.compare(baseline, other, realConfig()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corpus mismatch");
    }

    @Test
    void malformedJsonFailsLoudly() {
        assertThatThrownBy(
                        () ->
                                RunOutput.parse(
                                        new java.io.ByteArrayInputStream("{not json".getBytes()),
                                        "broken.json"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("broken.json");
    }

    @Test
    void legSetMismatchFailsLoudly() throws Exception {
        RunOutput baseline = fixture("baseline-sample.json");
        RunOutput subset =
                new RunOutput(baseline.runId(), baseline.corpus(), List.of(baseline.queries().get(0)));
        assertThatThrownBy(() -> ConvergenceRunner.compare(baseline, subset, realConfig()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing leg");
    }
}

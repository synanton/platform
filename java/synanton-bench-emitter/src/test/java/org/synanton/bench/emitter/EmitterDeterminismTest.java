package org.synanton.bench.emitter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A.6 acceptance: emitsByteIdenticalQ3AcrossSubprocessRuns — full 120-query
 * set (all modes × all filter types, incl. metadata + eligibility legs that
 * exercise the A.5 metadata columns), subprocess at -Xmx1g matching 028a.8.
 * A subset run would be trivial-green; 120 is the pin.
 */
class EmitterDeterminismTest {

    @Test
    void emitsByteIdenticalQ3AcrossSubprocessRuns() throws Exception {
        assertThat(DeterminismProbe.queries()).as("full 120-query set").hasSize(120);
        assertThat(
                        DeterminismProbe.queries().stream()
                                .filter(q -> q.filter().equals("metadata"))
                                .count())
                .as("metadata legs present (A.5 path exercised)")
                .isPositive();
        assertThat(
                        DeterminismProbe.queries().stream()
                                .filter(q -> q.filter().equals("eligibility"))
                                .count())
                .as("eligibility legs present")
                .isPositive();

        String inProcess = DeterminismProbe.runSha(DeterminismProbe.engine());

        String javaBin = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");
        Process proc =
                new ProcessBuilder(
                                javaBin, "-Xmx1g", "-cp", classpath,
                                DeterminismProbe.class.getName())
                        .redirectErrorStream(true)
                        .start();
        String stdout = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertThat(exit).as("1g subprocess exit 0; output:\n" + stdout).isZero();
        String subSha =
                stdout.lines()
                        .filter(l -> l.startsWith("det-sha="))
                        .map(l -> l.substring("det-sha=".length()).trim())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("no det-sha in:\n" + stdout));
        assertThat(subSha)
                .as("cross-process normalized Q3 sha matches in-process")
                .isEqualTo(inProcess);
        System.out.println("emitter-det-sha=" + inProcess);
    }
}

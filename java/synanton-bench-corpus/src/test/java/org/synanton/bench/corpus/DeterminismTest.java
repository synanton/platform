package org.synanton.bench.corpus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 028a.8 determinism (spec §6c): seed-42 byte-identical regeneration.
 *
 * <p>Stronger than same-JVM-twice: the second run happens in a SUBPROCESS
 * with different heap flags ({@code -Xmx1g} vs this worker's 2g), so hidden
 * heap/GC/JIT state cannot leak across. Compared artifact is per-file sha256
 * (streamed — no giant in-memory lists). Cross-machine/cross-language remains
 * verified by port, not here; .11 records that boundary.
 */
class DeterminismTest {

    private static final List<String> FILES =
            List.of("documents.jsonl", "chunks.jsonl", "golden-queries.jsonl");

    private static List<String> emitTo(Path dir) throws Exception {
        return CorpusIo.emitAll(CorpusIo.buildSkeletons(), dir);
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.delete(p);
                                } catch (Exception e) {
                                    // Teardown WARN rule: log, never swallow silently.
                                    System.err.println("WARN: det temp cleanup failed: " + e.getMessage());
                                }
                            });
        } catch (Exception e) {
            System.err.println("WARN: det temp walk failed: " + e.getMessage());
        }
    }

    @Test
    void sameProcessTwiceByteIdentical() throws Exception {
        Path a = Files.createTempDirectory("corpus-det-a");
        Path b = Files.createTempDirectory("corpus-det-b");
        try {
            assertThat(emitTo(a)).as("run A vs run B file shas").isEqualTo(emitTo(b));
        } finally {
            deleteTree(a);
            deleteTree(b);
        }
    }

    @Test
    void subprocessDifferentHeapByteIdentical() throws Exception {
        Path expected = Files.createTempDirectory("corpus-det-exp");
        Path actual = Files.createTempDirectory("corpus-det-act");
        List<String> expectedShas;
        try {
            expectedShas = emitTo(expected);
        } finally {
            // Keep expected dir for comparison; deleted at the end.
        }
        try {
            String javaBin = System.getProperty("java.home") + "/bin/java";
            String classpath = System.getProperty("java.class.path");
            Process proc =
                    new ProcessBuilder(
                                    javaBin, "-Xmx1g", "-cp", classpath,
                                    CorpusEmit.class.getName(), actual.toString())
                            .redirectErrorStream(true)
                            .start();
            String stdout = new String(proc.getInputStream().readAllBytes());
            int exit = proc.waitFor();
            assertThat(exit).as("subprocess exit 0; output:\n" + stdout).isZero();
            for (String f : FILES) {
                assertThat(Files.exists(actual.resolve(f))).as("subprocess emitted " + f).isTrue();
            }
            List<String> actualShas =
                    List.of(
                            CorpusIo.sha256File(actual.resolve("documents.jsonl")),
                            CorpusIo.sha256File(actual.resolve("chunks.jsonl")),
                            CorpusIo.sha256File(actual.resolve("golden-queries.jsonl")));
            assertThat(actualShas)
                    .as("cross-process file shas (heap flags differ) match in-process")
                    .isEqualTo(expectedShas);
            System.out.println("determinism shas=" + expectedShas);
        } finally {
            deleteTree(expected);
            deleteTree(actual);
        }
    }
}

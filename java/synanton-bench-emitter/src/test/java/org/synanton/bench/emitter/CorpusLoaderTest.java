package org.synanton.bench.emitter;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A.2 acceptance: loadsV1CorpusStreamingAtOneGigabyteHeap (+ missing-manifest
 * negative). The heap leg runs {@link CorpusLoadProbe} in a subprocess at
 * -Xmx1g (028a.8 pattern) — same-JVM-only would not pin the bound.
 */
class CorpusLoaderTest {

    private static Path fixtureDir() throws Exception {
        URI uri =
                CorpusLoaderTest.class.getResource("/corpus-mini/manifest.json").toURI();
        return Paths.get(uri).getParent();
    }

    @Test
    void loadsV1CorpusStreamingAtOneGigabyteHeap() throws Exception {
        Path dir = fixtureDir();
        CorpusLoader.Manifest manifest = CorpusLoader.loadManifest(dir);
        assertThat(manifest.corpusVersion()).isEqualTo("ydb-poc-corpus-v1-mini");
        assertThat(manifest.seed()).isEqualTo(42);
        List<String> seen = new ArrayList<>();
        long count = CorpusLoader.streamChunks(dir, row -> seen.add(row.chunkId()));
        assertThat(count).isEqualTo(4);
        assertThat(seen).containsExactly("c1", "c2", "c3", "c4");

        // Subprocess leg at -Xmx1g: same load must succeed under the bound.
        String javaBin = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");
        // Test classes + resources are on the worker classpath; main classes too.
        Process proc =
                new ProcessBuilder(
                                javaBin, "-Xmx1g", "-cp", classpath,
                                CorpusLoadProbe.class.getName(), dir.toString())
                        .redirectErrorStream(true)
                        .start();
        String stdout = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertThat(exit).as("1g subprocess exit 0; output:\n" + stdout).isZero();
        assertThat(stdout)
                .as("subprocess loaded all rows under 1g")
                .contains("corpus_version=ydb-poc-corpus-v1-mini chunks=4");
    }

    @Test
    void missingManifestNamesExpectedPath() throws Exception {
        Path empty = Files.createTempDirectory("corpus-nomanifest");
        try {
            assertThatThrownBy(() -> CorpusLoader.loadManifest(empty))
                    .isInstanceOf(CorpusLoader.MissingManifestException.class)
                    .hasMessageContaining("manifest.json")
                    .hasMessageContaining(empty.toString());
        } finally {
            Files.delete(empty);
        }
    }
}

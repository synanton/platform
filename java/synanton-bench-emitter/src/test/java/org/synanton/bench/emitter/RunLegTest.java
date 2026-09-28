package org.synanton.bench.emitter;

import java.net.URI;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CLI.1 invocation boundary: bad invocations fail fast with named errors
 * (missing --engine, unknown engine, missing manifest) — never a wall hang.
 * Full legs run out-of-Gradle via plain java (see CLI.1 spec).
 */
class RunLegTest {

    @Test
    void unknownEngineFailsFast() throws Exception {
        // Manifest validates before engine construction (correct order);
        // point at the mini fixture so the run reaches the engine switch.
        URI uri = RunLegTest.class.getResource("/corpus-mini/manifest.json").toURI();
        String fixture = Paths.get(uri).getParent().toString();
        assertThatThrownBy(
                        () ->
                                RunLeg.main(
                                        new String[] {
                                            "--engine", "bogus", "--corpus", fixture, "--out", "/tmp/x.json"
                                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown --engine");
    }

    @Test
    void missingOutFailsFast() {
        assertThatThrownBy(
                        () ->
                                RunLeg.main(
                                        new String[] {"--engine", "ydb", "--corpus", "/tmp", "--out", ""}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required --out");
    }

    @Test
    void missingManifestFailsFast() {
        assertThatThrownBy(
                        () ->
                                RunLeg.main(
                                        new String[] {
                                            "--engine",
                                            "cassandra",
                                            "--corpus",
                                            "/tmp/definitely-not-a-corpus",
                                            "--out",
                                            "/tmp/x.json"
                                        }))
                .hasMessageContaining("manifest");
    }
}

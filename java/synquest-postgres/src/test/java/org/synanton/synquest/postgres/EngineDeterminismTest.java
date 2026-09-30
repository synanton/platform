package org.synanton.synquest.postgres;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG-POC-007-7b engine determinism (028a.8 pattern): same corpus, same
 * queries, byte-identical top-K across processes. Stronger than
 * same-JVM-twice — the rerun happens in a SUBPROCESS with {@code -Xmx1g}
 * (vs this worker's flags), so hidden heap/GC/JIT state cannot leak
 * across. The container is shared (live-test policy); determinism is a
 * property of the engine's outputs, not of fresh infrastructure.
 */
class EngineDeterminismTest extends QuestPostgresFixture {

    private static final String TENANT = "det";

    @BeforeAll
    static void seed() throws Exception {
        ensureStarted();
        resetTenants(TENANT);
        Random random = new Random(0xD37);
        var engine = QuestPostgresFixture.newEngine();
        List<ChunkProjection> batch = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            float[] embedding = new float[384];
            embedding[0] = 1.0f;
            for (int d = 1; d < 384; d++) {
                embedding[d] = (float) (random.nextGaussian() * 0.1);
            }
            batch.add(
                    new ChunkProjection(
                            ChunkId.of(String.format("det-%03d", i)),
                            DocumentId.of("doc-det-" + i),
                            TENANT,
                            "determinism alpha content number " + i + (i % 7 == 0 ? " extra beta" : ""),
                            Map.of("type", "note"),
                            embedding,
                            EmbeddingModelRef.of("m", "v1", "d"),
                            i,
                            GenerationId.of("gen-det")));
        }
        engine.upsert(batch).toCompletableFuture().join();
    }

    private static String runProbe(List<String> jvmArgs) throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(classpath);
        command.add(EngineDeterminismProbe.class.getName());
        // Container superuser creds stay in-test; the probe connects as app
        // (same least-privilege shape as the engine under test).
        command.add(QuestPostgresFixture.jdbcUrl());
        command.add("app");
        command.add("app");
        command.add(TENANT);
        Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
        String stdout = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertThat(exit).as("probe exit 0; output:\n" + stdout).isZero();
        return stdout;
    }

    @Test
    void subprocessDifferentHeapByteIdentical() throws Exception {
        // In-process baseline (default worker flags) vs subprocess (-Xmx1g):
        // same bytes or the engine has hidden nondeterminism.
        String baseline = runProbe(List.of());
        String constrained = runProbe(List.of("-Xmx1g"));
        assertThat(constrained).as("cross-process top-K identical").isEqualTo(baseline);
        assertThat(baseline.lines().count())
                .as("probe emitted lexical+vector+hybrid lines")
                .isGreaterThan(10);
    }

    @Test
    void sameJvmTwiceByteIdentical() throws Exception {
        assertThat(runProbe(List.of())).isEqualTo(runProbe(List.of()));
    }
}

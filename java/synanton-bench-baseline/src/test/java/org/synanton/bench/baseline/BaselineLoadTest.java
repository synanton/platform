package org.synanton.bench.baseline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.synanton.bench.emitter.CorpusLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 028b.2 acceptance: baselineLoadsV1CorpusStreamingViaSharedLoaderAtOneGigabyteHeap.
 * Wires the shared streaming loader (A.2) — no baseline-specific loader, no
 * in-memory corpus. The 1g ceiling is enforced by the module's test task
 * config (this whole suite runs under it); missing corpus skips visibly.
 */
class BaselineLoadTest {

    @Test
    void baselineLoadsV1CorpusStreamingViaSharedLoaderAtOneGigabyteHeap() throws Exception {
        String corpusEnv = System.getenv("CORPUS_DIR");
        assumeTrue(
                corpusEnv != null && Files.isRegularFile(Paths.get(corpusEnv, "manifest.json")),
                "v1 corpus absent (CORPUS_DIR) — visible skip, not green-silence");
        Path dir = Paths.get(corpusEnv);

        CorpusLoader.Manifest manifest = CorpusLoader.loadManifest(dir);
        assertThat(manifest.corpusVersion()).isEqualTo("ydb-poc-corpus-v1");

        long[] rows = {0};
        Set<String> tenants = new HashSet<>();
        CorpusLoader.streamChunks(
                dir,
                row -> {
                    rows[0]++;
                    tenants.add(row.tenantId());
                });
        assertThat(rows[0]).as("full 160k streamed, none held").isEqualTo(160_000);
        assertThat(tenants).as("all 50 tenants seen").hasSize(50);
        System.out.println(
                "BASELINE-LOAD version=" + manifest.corpusVersion() + " rows=" + rows[0]
                        + " tenants=" + tenants.size());
    }

    @Test
    void missingManifestSurfacesSharedError() {
        // The baseline surfaces the shared loader's named error, not its own.
        try {
            CorpusLoader.loadManifest(Paths.get("/tmp/definitely-not-a-corpus"));
            assertThat(false).as("should have thrown").isTrue();
        } catch (CorpusLoader.MissingManifestException e) {
            assertThat(e.getMessage()).contains("manifest.json");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

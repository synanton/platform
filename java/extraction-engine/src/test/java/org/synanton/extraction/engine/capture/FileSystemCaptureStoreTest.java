package org.synanton.extraction.engine.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.LlmUsage;
import org.synanton.extraction.engine.QuarantinedLine;
import org.synanton.extraction.engine.TextSegment;

class FileSystemCaptureStoreTest {

    @Test
    void writesInsideTenantAndSourceVersionScope(@TempDir Path root) throws Exception {
        FileSystemCaptureStore store = new FileSystemCaptureStore(root);
        store.writeQuarantine(context("tenant-a", "version-1"),
                new TextSegment("chunk-1", "bad line", 0), LlmUsage.NONE,
                List.of(new QuarantinedLine(1, "bad line", "MALFORMED_LINE", "detail", "STRUCTURAL")));

        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).toList())
                    .singleElement()
                    .satisfies(path -> assertThat(path.toString())
                            .contains("tenant-a")
                            .contains("version-1")
                            .endsWith(".jsonl"));
        }
    }

    @Test
    void sourceDeletionIsScopedAndIdempotent(@TempDir Path root) throws Exception {
        FileSystemCaptureStore store = new FileSystemCaptureStore(root);
        store.writeQuarantine(context("tenant-a", "version-1"), segment(), LlmUsage.NONE, lines());
        store.writeQuarantine(context("tenant-a", "version-2"), segment(), LlmUsage.NONE, lines());
        store.writeQuarantine(context("tenant-b", "version-1"), segment(), LlmUsage.NONE, lines());

        store.deleteSource("tenant-a", "version-1");
        store.deleteSource("tenant-a", "version-1");

        try (var files = Files.walk(root)) {
            List<Path> remaining = files.filter(Files::isRegularFile).toList();
            assertThat(remaining).hasSize(2);
            assertThat(remaining).allSatisfy(path -> assertThat(path.toString())
                    .doesNotContain("tenant-a/version-1"));
        }
    }

    @Test
    void tenantSegmentsCannotEscapeRoot(@TempDir Path root) {
        FileSystemCaptureStore store = new FileSystemCaptureStore(root);
        store.writeQuarantine(context("../evil", "../../escape"), segment(), LlmUsage.NONE, lines());

        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).toList())
                    .allSatisfy(path -> assertThat(path.normalize().startsWith(root.normalize())).isTrue());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void expiryDeletesOnlyFilesOlderThanCutoff(@TempDir Path root) throws Exception {
        FileSystemCaptureStore store = new FileSystemCaptureStore(root);
        store.writeQuarantine(context("tenant-a", "version-1"), segment(), LlmUsage.NONE, lines());
        Path old;
        Path fresh;
        try (var files = Files.walk(root)) {
            List<Path> paths = files.filter(Files::isRegularFile).toList();
            old = paths.get(0);
            Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(86400 * 31)));
            store.writeQuarantine(context("tenant-a", "version-2"), segment(), LlmUsage.NONE, lines());
            try (var more = Files.walk(root)) {
                fresh = more.filter(Files::isRegularFile)
                        .filter(path -> !path.equals(old)).findFirst().orElseThrow();
            }
        }

        int deleted = store.expireOlderThan(Instant.now().minusSeconds(86400 * 30));

        assertThat(deleted).isEqualTo(1);
        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
    }

    private static ExtractionContext context(String tenant, String version) {
        return new ExtractionContext(tenant, "doc", java.util.Set.of(), version);
    }

    private static TextSegment segment() {
        return new TextSegment("chunk-1", "bad line", 0);
    }

    private static List<QuarantinedLine> lines() {
        return List.of(new QuarantinedLine(1, "bad line", "MALFORMED_LINE", "detail",
                "STRUCTURAL"));
    }
}

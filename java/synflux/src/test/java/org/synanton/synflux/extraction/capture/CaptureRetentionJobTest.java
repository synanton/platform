package org.synanton.synflux.extraction.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.LlmUsage;
import org.synanton.extraction.engine.QuarantinedLine;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.capture.FileSystemCaptureStore;
import org.synanton.synflux.config.ExtractionEngineProperties;

class CaptureRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

    @Test
    void expiresOnlyFilesOlderThanThirtyDays(@TempDir Path root) throws Exception {
        FileSystemCaptureStore store = new FileSystemCaptureStore(root);
        store.writeQuarantine(context("tenant-a", "version-1"), segment(), LlmUsage.NONE, lines());
        store.writeQuarantine(context("tenant-a", "version-2"), segment(), LlmUsage.NONE, lines());
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile).sorted().toList();
        }
        Files.setLastModifiedTime(files.get(0), FileTime.from(NOW.minusSeconds(31L * 86400)));
        Files.setLastModifiedTime(files.get(1), FileTime.from(NOW.minusSeconds(29L * 86400)));

        job(root, true, null).run();

        assertThat(files.get(0)).doesNotExist();
        assertThat(files.get(1)).exists();
    }

    @Test
    void doesNotExpireWhenCaptureDisabledOrProduction(@TempDir Path root) throws Exception {
        FileSystemCaptureStore store = new FileSystemCaptureStore(root);
        store.writeQuarantine(context("tenant-a", "version-1"), segment(), LlmUsage.NONE, lines());
        Path file;
        try (var paths = Files.walk(root)) {
            file = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Files.setLastModifiedTime(file, FileTime.from(NOW.minusSeconds(31L * 86400)));

        job(root, false, null).run();
        job(root, true, "production").run();

        assertThat(file).exists();
    }

    private static CaptureRetentionJob job(Path root, boolean enabled, String environment) {
        ExtractionEngineProperties properties = new ExtractionEngineProperties(
                "llm", "http://localhost:8000/v1", "qwen3-extraction", 120, 2048,
                root.toString(), enabled, environment);
        return new CaptureRetentionJob(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExtractionContext context(String tenant, String version) {
        return new ExtractionContext(tenant, "doc", Set.of(), version);
    }

    private static TextSegment segment() {
        return new TextSegment("chunk-1", "bad line", 0);
    }

    private static List<QuarantinedLine> lines() {
        return List.of(new QuarantinedLine(1, "bad line", "MALFORMED_LINE", "detail",
                "STRUCTURAL"));
    }
}

package org.synanton.synvault.ydb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Permanent quota guard (belt-and-suspenders behind TRUNCATE hygiene): fails
 * loudly while the count is actionable, not at 10k when DDL stops working.
 * Skips (visibly) when the local viewer endpoint is unreachable — the guard
 * targets the PoC container, not arbitrary deployments.
 */
class YdbQuotaGuardTest {

    private static final long PATH_THRESHOLD = 9000;
    /** Tolerated growth between consecutive runs (manual probes, one-off runs). */
    private static final long GROWTH_TOLERANCE = 25;
    private static final java.nio.file.Path BASELINE =
            java.nio.file.Paths.get("build", "quota-baseline.txt");

    @Test
    void pathCountBelowThreshold() {
        long count;
        try {
            count = countTestPaths();
        } catch (Exception e) {
            assumeTrue(false, "viewer unreachable, quota guard skipped: " + e.getMessage());
            return;
        }
        assertThat(count)
                .as("test path count approaching 10k quota — clean historical debris")
                .isLessThan(PATH_THRESHOLD);
    }

    /**
     * Delta guard: consecutive runs must not grow the path count beyond manual-run
     * tolerance. This catches the original bug class (unbounded per-run growth)
     * within one or two reruns; the absolute check above would take ~875.
     */
    @Test
    void pathCountDoesNotGrowBetweenRuns() {
        long count;
        try {
            count = countTestPaths();
        } catch (Exception e) {
            assumeTrue(false, "viewer unreachable, quota guard skipped: " + e.getMessage());
            return;
        }
        long baseline = readBaseline().orElse(count);
        assertThat(count)
                .as("path growth since last run (baseline " + baseline + ") exceeds manual-run tolerance")
                .isLessThanOrEqualTo(baseline + GROWTH_TOLERANCE);
        writeBaseline(count);
    }

    private static java.util.OptionalLong readBaseline() {
        try {
            String raw = java.nio.file.Files.readString(BASELINE).trim();
            return java.util.OptionalLong.of(Long.parseLong(raw));
        } catch (Exception e) {
            return java.util.OptionalLong.empty();
        }
    }

    private static void writeBaseline(long count) {
        try {
            java.nio.file.Files.createDirectories(BASELINE.getParent());
            java.nio.file.Files.writeString(
                    BASELINE, Long.toString(count),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
            // Best effort: a missing baseline degrades to absolute-only guarding.
        }
    }

    static long countTestPaths() throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request =
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(
                                        "http://localhost:8765/viewer/json/describe?path=/local&enums=true"))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .GET()
                        .build();
        String body = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"Name\":\"([^\"]+)\"").matcher(body);
        java.util.Set<String> names = new java.util.HashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names.stream()
                .filter(n -> n.matches("^(poc|q|ft_|gate0|hyb_|mig|drop|t_)[0-9a-z_]*"))
                .count();
    }
}

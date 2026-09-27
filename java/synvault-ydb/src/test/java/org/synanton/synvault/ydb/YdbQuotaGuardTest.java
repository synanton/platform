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

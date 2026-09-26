package org.synanton.storage.testkit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Required assertion shape for any index-dependent test (YDB-POC-024B pattern):
 * correctness alone passes on a full scan, so the test must also pin the
 * mechanism — the query plan must resolve to the index, not the base table.
 * A commit that drops the VIEW, breaks filter composition, or degrades to a
 * scan fails here visibly instead of quietly.
 */
public final class PlanAssertions {
    private PlanAssertions() {}

    /**
     * Asserts the plan references every expected index/table marker.
     *
     * @param plan    EXPLAIN output (AST and/or plan text)
     * @param markers index implementation markers that must appear,
     *                e.g. {@code "v_vec"}, {@code "ft"}
     */
    public static void assertUsesIndexes(String plan, String... markers) {
        assertThat(plan).as("query plan must be present").isNotBlank();
        for (String marker : markers) {
            assertThat(plan)
                    .as("plan must resolve to index '" + marker + "', not a scan")
                    .contains(marker);
        }
    }

    /**
     * Asserts the plan contains none of the given full-scan markers.
     */
    public static void assertNoFullScan(String plan, List<String> scanMarkers) {
        for (String marker : scanMarkers) {
            assertThat(plan)
                    .as("plan must not fall back to full scan (" + marker + ")")
                    .doesNotContain(marker);
        }
    }
}

package org.synanton.bench.emitter;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A.5 acceptance: rejectsQ3OutputWithEligibleSetDivergence — re-derived from
 * chunk rows (option 1), never fixture-vs-fixture. Positive green, corrupted
 * negative control naming query + ids.
 */
class EligibilityValidatorTest {

    private static Path fixtureDir() throws Exception {
        URI uri = EligibilityValidatorTest.class.getResource("/corpus-mini/manifest.json").toURI();
        return Paths.get(uri).getParent();
    }

    @Test
    void rejectsQ3OutputWithEligibleSetDivergence() throws Exception {
        Path dir = fixtureDir();

        // Tenant scope: eligible = tenant_07 rows.
        EligibilityValidator.Mismatch ok =
                EligibilityValidator.validate(dir, List.of("tenant_07"), Map.of(), List.of("c1", "c2"));
        assertThat(ok.clean()).as("exact re-derivation passes").isTrue();

        // Metadata predicate intersects scope.
        EligibilityValidator.Mismatch meta =
                EligibilityValidator.validate(
                        dir, List.of("tenant_07", "tenant_11"), Map.of("doc_type", "memo"),
                        List.of("c1", "c3"));
        assertThat(meta.clean()).as("metadata re-derivation passes").isTrue();

        // Corrupted: one missing (c2 dropped), one intruder (c3 added).
        EligibilityValidator.Mismatch bad =
                EligibilityValidator.validate(dir, List.of("tenant_07"), Map.of(), List.of("c1", "c3"));
        assertThat(bad.clean()).as("divergence detected").isFalse();
        assertThat(bad.missing()).containsExactly("c2");
        assertThat(bad.extra()).containsExactly("c3");
        assertThat(bad.toString()).contains("c2").contains("c3");
    }
}

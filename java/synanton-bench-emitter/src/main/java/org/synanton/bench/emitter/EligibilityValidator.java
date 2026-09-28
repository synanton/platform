package org.synanton.bench.emitter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A.5 ground-truth validator (the leakage barrier). Re-derives the eligible
 * set from corpus chunk rows + filter rules (spec §5: scope bounds, predicates
 * never widen) and set-compares against the claimed eligible ids.
 *
 * <p>Independence (pinned pre-A.5): the executor carries eligible ids from
 * the golden-queries fixture; this validator NEVER reads that fixture field —
 * it recomputes from chunk metadata. Fixture-vs-itself comparison would pass
 * trivially (phantom-SKIP family); re-derivation catches a fixture that is
 * internally wrong. The two implementations were written independently on
 * different branches (generator: DESIGN-YDB-028a-impl; here: shared module).
 */
public final class EligibilityValidator {

    private EligibilityValidator() {}

    public record Mismatch(Set<String> missing, Set<String> extra) {
        boolean clean() {
            return missing.isEmpty() && extra.isEmpty();
        }

        @Override
        public String toString() {
            return "missing=" + missing + " extra=" + extra;
        }
    }

    /**
     * Validates claimed eligible ids for one query: empty scope means the full
     * corpus (filter=none legs); otherwise tenants in scope intersected with
     * the metadata predicate.
     */
    public static Mismatch validate(
            Path corpusDir,
            List<String> tenantScope,
            Map<String, String> metadataPredicate,
            List<String> claimedEligibleIds)
            throws Exception {
        Set<String> recomputed = new HashSet<>();
        final List<String> scope =
                (tenantScope == null || tenantScope.isEmpty()) ? null : tenantScope;
        CorpusLoader.streamChunks(
                corpusDir,
                row -> {
                    if (scope != null && !scope.contains(row.tenantId())) {
                        return;
                    }
                    if (!metadataPredicate.entrySet().stream()
                            .allMatch(e -> e.getValue().equals(rowMetadata(row, e.getKey())))) {
                        return;
                    }
                    recomputed.add(row.chunkId());
                });
        Set<String> claimed = new HashSet<>(claimedEligibleIds);
        Set<String> missing = new TreeSet<>(recomputed);
        missing.removeAll(claimed);
        Set<String> extra = new TreeSet<>(claimed);
        extra.removeAll(recomputed);
        return new Mismatch(missing, extra);
    }

    private static String rowMetadata(CorpusLoader.ChunkRow row, String key) {
        // Metadata rides on the row (loader collects non-core string fields);
        // absent attribute ⇒ no match (fail-closed, never fail-open).
        return row.metadata().getOrDefault(key, "");
    }
}

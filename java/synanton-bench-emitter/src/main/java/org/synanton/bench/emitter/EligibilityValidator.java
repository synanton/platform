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
     * Corpus index for single-pass validation (RunLeg wiring): one stream
     * builds it, per-query checks run in memory. Same match semantics as
     * {@link #validate(Path, List, Map, List)} — the predicate core is
     * shared, never duplicated.
     */
    public record IndexedRow(String tenant, Map<String, String> meta, String text) {}

    public static Map<String, IndexedRow> loadIndex(Path corpusDir) throws Exception {
        Map<String, IndexedRow> index = new java.util.HashMap<>();
        CorpusLoader.streamChunks(
                corpusDir,
                row -> index.put(row.chunkId(),
                        new IndexedRow(row.tenantId(), Map.copyOf(row.metadata()), row.text())));
        return Map.copyOf(index);
    }

    /** In-memory twin of {@link #validate(Path, List, Map, List)} (same rules). */
    public static Mismatch validate(
            Map<String, IndexedRow> index,
            List<String> tenantScope,
            Map<String, String> metadataPredicate,
            List<String> claimedEligibleIds) {
        Set<String> recomputed = new HashSet<>();
        for (var entry : index.entrySet()) {
            if (matches(entry.getValue(), tenantScope, metadataPredicate)) {
                recomputed.add(entry.getKey());
            }
        }
        Set<String> claimed = new HashSet<>(claimedEligibleIds);
        Set<String> missing = new TreeSet<>(recomputed);
        missing.removeAll(claimed);
        Set<String> extra = new TreeSet<>(claimed);
        extra.removeAll(recomputed);
        return new Mismatch(missing, extra);
    }

    private static boolean matches(
            IndexedRow row, List<String> tenantScope, Map<String, String> metadataPredicate) {
        if (tenantScope != null && !tenantScope.isEmpty() && !tenantScope.contains(row.tenant())) {
            return false;
        }
        return metadataPredicate.entrySet().stream()
                .allMatch(e -> e.getValue().equals(row.meta().getOrDefault(e.getKey(), "")));
    }
    /** Path-based entry: streams once, then delegates to the shared core. */
    public static Mismatch validate(
            Path corpusDir,
            List<String> tenantScope,
            Map<String, String> metadataPredicate,
            List<String> claimedEligibleIds)
            throws Exception {
        Set<String> recomputed = new HashSet<>();
        CorpusLoader.streamChunks(
                corpusDir,
                row -> {
                    if (matches(
                            new IndexedRow(row.tenantId(), Map.copyOf(row.metadata()), row.text()),
                            tenantScope,
                            metadataPredicate)) {
                        recomputed.add(row.chunkId());
                    }
                });
        Set<String> claimed = new HashSet<>(claimedEligibleIds);
        Set<String> missing = new TreeSet<>(recomputed);
        missing.removeAll(claimed);
        Set<String> extra = new TreeSet<>(claimed);
        extra.removeAll(recomputed);
        return new Mismatch(missing, extra);
    }
}

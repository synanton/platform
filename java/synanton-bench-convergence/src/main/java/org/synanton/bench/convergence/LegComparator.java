package org.synanton.bench.convergence;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-leg comparison primitives. Both are pinned by PG-POC-017 review — do not
 * "relax" without a proposal change:
 *
 * <ul>
 *   <li>Overlap is <b>set-based</b>, not sequence-based: top-K order is
 *       post-retrieval sorted, so ties never affect overlap. {@code |A ∩ B| /
 *       max(|A|, |B|)}; two empty lists overlap 1.0 (nothing to disagree on),
 *       empty-vs-nonempty overlaps 0.0.
 *   <li>Eligible-set identity is <b>exact set equality</b>. Any asymmetric
 *       difference is a hard fail (leakage or scope bug), never a caveat.
 * </ul>
 */
public final class LegComparator {

    private LegComparator() {}

    /** Set-based top-K id overlap in [0, 1]. Scores and ranks are ignored. */
    public static double overlap(List<TopKEntry> a, List<TopKEntry> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<String> aIds = a.stream().map(TopKEntry::chunkId).collect(Collectors.toSet());
        Set<String> bIds = b.stream().map(TopKEntry::chunkId).collect(Collectors.toSet());
        Set<String> intersection = new HashSet<>(aIds);
        intersection.retainAll(bIds);
        return (double) intersection.size() / Math.max(aIds.size(), bIds.size());
    }

    /** Exact eligible-set equality. */
    public static boolean eligibleSetsIdentical(List<String> a, List<String> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
    }
}

package org.synanton.bench.emitter;

import java.util.List;
import java.util.Map;

/**
 * Per-query retrievability guard (shared; answers the B.2 exemption-list
 * question without a hardcoded list). For lexical-filtered legs, emptiness
 * must coincide with ground truth: top-K empty ⟺ zero eligible chunks contain
 * all query terms. Both directions enforced — a non-empty result with zero
 * retrievable is as wrong as an empty result with retrievable present.
 *
 * <p>For non-lexical-filtered legs, top-K must be non-empty (KNN continuity:
 * nearest neighbors always exist; minScore=0 keeps non-negative scores).
 * Lexical-filtered legs are exempt from the non-empty rule <i>only</i> via
 * the equivalence above — never by class.
 */
public final class RetrievabilityGuard {

    private RetrievabilityGuard() {}

    public record Verdict(String queryId, long retrievable, int topK, boolean pass, String detail) {}

    /**
     * @param idToText corpus chunk id → text (streamed once by the caller)
     * @param isLexicalFiltered true for lexical mode with non-none filter
     */
    public static Verdict check(
            String queryId,
            String queryText,
            List<String> eligibleIds,
            List<String> topKIds,
            Map<String, String> idToText,
            boolean isLexicalFiltered) {
        if (!isLexicalFiltered) {
            boolean pass = !topKIds.isEmpty();
            return new Verdict(
                    queryId, -1, topKIds.size(), pass,
                    pass ? "knn-continuity holds"
                            : "EMPTY top-K on a KNN-capable leg — defect, not structural");
        }
        List<String> terms = List.of(queryText.strip().split("\\s+"));
        long retrievable =
                eligibleIds.stream()
                        .filter(
                                id -> {
                                    String text = idToText.get(id);
                                    if (text == null) {
                                        return false;
                                    }
                                    String padded = " " + text + " ";
                                    return terms.stream().allMatch(t -> padded.contains(" " + t + " "));
                                })
                        .count();
        boolean empty = topKIds.isEmpty();
        boolean pass = empty == (retrievable == 0);
        return new Verdict(
                queryId, retrievable, topKIds.size(), pass,
                pass
                        ? (empty ? "structurally empty (0 retrievable — correct)"
                                : "populated with retrievable present")
                        : ("MISMATCH: empty=" + empty + " retrievable=" + retrievable
                                + " — bug, not structure"));
    }
}

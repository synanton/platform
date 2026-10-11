package org.synanton.synquest.service;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import java.util.*;

public class RrfFusion {

    /**
     * Combines dense and lexical TopDocs using Reciprocal Rank Fusion.
     * score(doc) = sum of 1/(rrf_k + rank_i) across result lists.
     *
     * <p>FUTURE (option B, recorded not implemented): score-based fusion —
     * fuse normalized dense-cosine + BM25 scores instead of ranks. Needs
     * per-leg score calibration that does not exist yet, and breaks the
     * score-space portability the rank formulation preserves (PN-11).
     * Do not attempt without a calibration corpus.
     */
    public static List<FusedHit> combine(
            TopDocs dense,
            TopDocs lexical,
            int topK,
            int rrfK) {
        return combine(dense, lexical, topK, rrfK, 1.0);
    }

    /**
     * Same, with a single-leg boost: DENSE-only hits (present in the dense
     * leg, absent from lexical) get their fused score multiplied by
     * {@code singleLegBoost}. 1.0 reproduces classic RRF. 2.0 restores a
     * dense-only hit to dual-leg parity for its rank.
     *
     * <p>Asymmetric by design (measured, F-tune): boosting lexical-only hits
     * promotes BM25 tail noise above dual-leg substance (boost 2.0 put
     * lexical ranks 4–10 over genuine dual-leg hits). Dense-only hits are
     * trusted — term mismatch with semantic support (synonyms, cross-domain
     * vocabulary like the q2 pharma contract). Lexical-only hits keep their
     * classic weight.
     */
    public static List<FusedHit> combine(
            TopDocs dense,
            TopDocs lexical,
            int topK,
            int rrfK,
            double singleLegBoost) {
        if (singleLegBoost < 1.0) {
            throw new IllegalArgumentException(
                    "singleLegBoost must be >= 1.0 (got " + singleLegBoost + ")");
        }

        Map<Integer, MutableHit> hits = new LinkedHashMap<>();

        rankInto(hits, dense, true, rrfK);
        rankInto(hits, lexical, false, rrfK);

        if (singleLegBoost != 1.0) {
            for (MutableHit h : hits.values()) {
                boolean denseOnly = h.rankLexical == Integer.MAX_VALUE
                        && h.rankDense != Integer.MAX_VALUE;
                if (denseOnly) {
                    h.rrfScore *= singleLegBoost;
                }
            }
        }

        return hits.values().stream()
                .sorted(Comparator.comparingDouble((MutableHit h) -> h.rrfScore).reversed())
                .limit(topK)
                .map(h -> new FusedHit(
                        h.docId,
                        h.rrfScore,
                        h.denseScore,
                        h.lexicalScore,
                        h.rankDense,
                        h.rankLexical))
                .toList();
    }

    private static void rankInto(Map<Integer, MutableHit> hits, TopDocs topDocs, boolean isDense, int rrfK) {
        ScoreDoc[] docs = topDocs.scoreDocs;
        for (int rank = 0; rank < docs.length; rank++) {
            ScoreDoc sd = docs[rank];
            MutableHit h = hits.computeIfAbsent(sd.doc, MutableHit::new);
            double contribution = 1.0 / (rrfK + rank + 1);
            h.rrfScore += contribution;
            if (isDense) {
                h.denseScore = sd.score;
                h.rankDense = rank + 1;
            } else {
                h.lexicalScore = sd.score;
                h.rankLexical = rank + 1;
            }
        }
    }

    public record FusedHit(
            int docId,
            double rrfScore,
            float denseScore,
            float lexicalScore,
            int rankDense,
            int rankLexical) {}

    private static class MutableHit {
        final int docId;
        double rrfScore;
        float denseScore;
        float lexicalScore;
        int rankDense = Integer.MAX_VALUE;
        int rankLexical = Integer.MAX_VALUE;

        MutableHit(int docId) {
            this.docId = docId;
        }
    }
}

package org.synanton.synquest.service;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import java.util.*;

public class RrfFusion {

    /**
     * Combines dense and lexical TopDocs using Reciprocal Rank Fusion.
     * score(doc) = sum of 1/(rrf_k + rank_i) across result lists.
     */
    public static List<FusedHit> combine(
            TopDocs dense,
            TopDocs lexical,
            int topK,
            int rrfK) {

        Map<Integer, MutableHit> hits = new LinkedHashMap<>();

        rankInto(hits, dense, true, rrfK);
        rankInto(hits, lexical, false, rrfK);

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

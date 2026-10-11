package org.synanton.synquest.service;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RrfFusionTest {

    private static TopDocs topDocs(int... docIds) {
        ScoreDoc[] docs = new ScoreDoc[docIds.length];
        for (int i = 0; i < docIds.length; i++) {
            docs[i] = new ScoreDoc(docIds[i], docIds.length - i);
        }
        return new TopDocs(new TotalHits(docIds.length, TotalHits.Relation.EQUAL_TO), docs);
    }

    @Test
    void combinesDisjointLists() {
        TopDocs dense = topDocs(1, 2, 3);
        TopDocs lexical = topDocs(4, 5, 6);
        List<RrfFusion.FusedHit> hits = RrfFusion.combine(dense, lexical, 5, 60);
        assertThat(hits).hasSize(5);
        // All docs should have non-zero rrf score
        hits.forEach(h -> assertThat(h.rrfScore()).isPositive());
    }

    @Test
    void overlappingDocsScoreHigher() {
        // Doc 1 appears in both lists → it should outscore docs only in one
        TopDocs dense = topDocs(1, 2, 3);
        TopDocs lexical = topDocs(1, 4, 5);
        List<RrfFusion.FusedHit> hits = RrfFusion.combine(dense, lexical, 3, 60);
        assertThat(hits.get(0).docId()).isEqualTo(1);
        assertThat(hits.get(0).rrfScore())
                .isGreaterThan(hits.get(1).rrfScore());
    }

    @Test
    void respectsTopKLimit() {
        TopDocs dense = topDocs(1, 2, 3, 4, 5);
        TopDocs lexical = topDocs(6, 7, 8, 9, 10);
        List<RrfFusion.FusedHit> hits = RrfFusion.combine(dense, lexical, 3, 60);
        assertThat(hits).hasSize(3);
    }

    @Test
    void rrfScoreFormula() {
        // Doc 0 at rank 1 in dense only → score = 1/(60+1) ≈ 0.01639
        TopDocs dense = topDocs(0);
        TopDocs lexical = topDocs();
        List<RrfFusion.FusedHit> hits = RrfFusion.combine(dense, lexical, 1, 60);
        assertThat(hits.get(0).rrfScore()).isCloseTo(1.0 / 61.0, within(1e-9));
    }

    @Test
    void emptyInputsReturnEmpty() {
        TopDocs dense = topDocs();
        TopDocs lexical = topDocs();
        assertThat(RrfFusion.combine(dense, lexical, 20, 60)).isEmpty();
    }

    // F-tune option A acceptance tests.

    @Test
    void defaultBoostOnePreservesClassicRrf() {
        // 4-arg form must equal 5-arg form with 1.0: no behavior change at default.
        TopDocs dense = topDocs(1, 2, 3);
        TopDocs lexical = topDocs(2, 4, 5);
        List<RrfFusion.FusedHit> classic = RrfFusion.combine(dense, lexical, 5, 60);
        List<RrfFusion.FusedHit> boosted = RrfFusion.combine(dense, lexical, 5, 60, 1.0);
        assertThat(boosted).hasSize(classic.size());
        for (int i = 0; i < classic.size(); i++) {
            assertThat(boosted.get(i).docId()).isEqualTo(classic.get(i).docId());
            assertThat(boosted.get(i).rrfScore()).isCloseTo(classic.get(i).rrfScore(), within(1e-12));
        }
    }

    @Test
    void boostTwoRestoresSingleLegParity() {
        // Doc 9 dense-only at rank 1 (1/61) vs doc 8 dual-leg at ranks 3+3
        // (2/63): classic buries the single-leg hit; boost 2.0 flips it.
        TopDocs dense = topDocs(9, 1, 8);
        TopDocs lexical = topDocs(2, 3, 8);
        List<RrfFusion.FusedHit> classic = RrfFusion.combine(dense, lexical, 5, 60);
        assertThat(classic.get(0).docId()).isEqualTo(8);
        List<RrfFusion.FusedHit> boosted = RrfFusion.combine(dense, lexical, 5, 60, 2.0);
        assertThat(boosted.get(0).docId()).isEqualTo(9);
    }

    @Test
    void boostBelowOneRejected() {
        TopDocs dense = topDocs(1);
        TopDocs lexical = topDocs();
        assertThatThrownBy(() -> RrfFusion.combine(dense, lexical, 5, 60, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

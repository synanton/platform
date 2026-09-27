package org.synanton.bench.corpus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 028a.5 acceptance: format assertions + determinism. Correlation is 028a.7's job. */
class ChunkEmbeddingTest {

    @Test
    void dimensionEncodingAndNorm() {
        ChunkEmbedding emb = new ChunkEmbedding();
        String b64 = emb.embed("tok1 tok2 gold0a", 3, 5);
        double[] v = ChunkEmbedding.decode(b64);
        assertThat(v).hasSize(384);
        double norm = 0.0;
        for (double x : v) {
            norm += x * x;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-6));
        // Base64 of exactly 384 float32-LE: no decimal rendering anywhere.
        assertThat(java.util.Base64.getDecoder().decode(b64)).hasSize(384 * 4);
    }

    @Test
    void deterministicPerChunk() {
        ChunkEmbedding emb = new ChunkEmbedding();
        assertThat(emb.embed("tok1 tok2", 3, 5)).isEqualTo(emb.embed("tok1 tok2", 3, 5));
        assertThat(emb.embed("tok1 tok2", 3, 5)).isNotEqualTo(emb.embed("tok1 tok2", 3, 6));
    }

    @Test
    void plantedTermsClusterNearCentroid() {
        // Construction sanity (not the §6b gate): same-cluster planted terms
        // must be mutually nearer than background — else .7 cannot pass at any σ.
        TokenDirections dirs = new TokenDirections();
        double[] a = dirs.direction("gold9a");
        double[] b = dirs.direction("gold9b");
        double[] bg = dirs.direction("tok1234");
        assertThat(cosine(a, b)).as("same-cluster cone").isGreaterThan(cosine(a, bg));
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}

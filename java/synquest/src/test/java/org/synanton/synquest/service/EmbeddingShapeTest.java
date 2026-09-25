package org.synanton.synquest.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** EmbeddingShape: startup validation against the Lucene cap, truncation, fail-on-mismatch. */
class EmbeddingShapeTest {

    private static float[] ramp(int n) {
        float[] v = new float[n];
        for (int i = 0; i < n; i++) v[i] = i + 1;
        return v;
    }

    private static double norm(float[] v) {
        double s = 0;
        for (float x : v) s += (double) x * x;
        return Math.sqrt(s);
    }

    @Test
    void luceneCapIsEnforcedAtStartup() {
        assertThat(EmbeddingShape.LUCENE_MAX_DIMENSIONS).isEqualTo(1024);
        assertThatThrownBy(() -> new EmbeddingShape(2048, 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EMBED_DIM").hasMessageContaining("1024");
        assertThatThrownBy(() -> new EmbeddingShape(0, 0)).isInstanceOf(IllegalStateException.class);
        new EmbeddingShape(1024, 1024); // the cap itself is allowed
    }

    @Test
    void truncateDimMustEqualDim() {
        assertThatThrownBy(() -> new EmbeddingShape(768, 1024))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EMBED_TRUNCATE_DIM");
        assertThatThrownBy(() -> new EmbeddingShape(768, -1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void truncationCutsTheLeadingComponentsAndRenormalises() {
        float[] native2048 = ramp(2048);
        float[] fitted = new EmbeddingShape(1024, 1024).fit(native2048);

        assertThat(fitted).hasSize(1024);
        assertThat(norm(fitted)).isCloseTo(1.0, within(1e-5));
        // same direction as the first 1024 components (Matryoshka prefix), not a projection
        assertThat(fitted[1] / fitted[0]).isCloseTo(2.0f, within(1e-4f));
    }

    @Test
    void truncationIsANoOpForVectorsAlreadyAtDim() {
        float[] fitted = new EmbeddingShape(1024, 1024).fit(ramp(1024));
        assertThat(fitted).hasSize(1024);
        assertThat(norm(fitted)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void mismatchesThrowInsteadOfBeingDropped() {
        EmbeddingShape noTruncate = new EmbeddingShape(768, 0);
        assertThatThrownBy(() -> noTruncate.fit(ramp(2048)))
                .isInstanceOf(EmbeddingShape.DimensionMismatchException.class)
                .hasMessageContaining("EMBED_TRUNCATE_DIM=768");
        // truncation never pads: a shorter vector is the wrong model
        assertThatThrownBy(() -> new EmbeddingShape(1024, 1024).fit(ramp(768)))
                .isInstanceOf(EmbeddingShape.DimensionMismatchException.class);
        assertThatThrownBy(() -> noTruncate.fit(null)).isInstanceOf(EmbeddingShape.DimensionMismatchException.class);
    }
}

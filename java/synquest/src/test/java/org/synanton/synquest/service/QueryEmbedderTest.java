package org.synanton.synquest.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class QueryEmbedderTest {

    @Test
    void normaliseUnitVector() {
        float[] vec = {3.0f, 4.0f};
        float[] result = QueryEmbedder.normalise(vec);
        assertThat(result[0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(result[1]).isCloseTo(0.8f, within(1e-6f));
        double norm = 0.0;
        for (float v : result) norm += (double) v * v;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void normaliseAlreadyUnit() {
        float[] vec = {1.0f, 0.0f, 0.0f};
        float[] result = QueryEmbedder.normalise(vec);
        assertThat(result[0]).isCloseTo(1.0f, within(1e-6f));
    }

    @Test
    void normaliseZeroVector() {
        float[] vec = {0.0f, 0.0f, 0.0f};
        float[] result = QueryEmbedder.normalise(vec);
        // Should return original without division by zero
        assertThat(result).containsExactly(0.0f, 0.0f, 0.0f);
    }
}

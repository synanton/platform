package org.synanton.bench.emitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the fixture↔Q3 field-name boundary that silently emptied two legs'
 * eligible sets (baseline + YDB read {@code eligible_set} where the corpus
 * ships {@code eligible_chunk_ids}; null-tolerant parsing hid it, and
 * empty-vs-empty identity passed vacuously). All runners must call
 * {@link CorpusLoader#eligibleIds}, never inline field access.
 */
class CorpusLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void eligibleIdsReadsFixtureField() throws Exception {
        var q =
                MAPPER.readTree(
                        "{\"query_id\": \"q1\", \"eligible_chunk_ids\": [\"a\", \"b\"]}");
        assertThat(CorpusLoader.eligibleIds(q)).containsExactly("a", "b");
    }

    @Test
    void eligibleIdsFailsLoudlyOnMissingField() throws Exception {
        var q = MAPPER.readTree("{\"query_id\": \"q1\"}");
        assertThatThrownBy(() -> CorpusLoader.eligibleIds(q))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eligible_chunk_ids");
    }
}

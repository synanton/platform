package org.synanton.relix.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRefCounterTest {

    @Test
    void shouldDetachWhenCountReachesZero() {
        SourceRefCounter counter = new SourceRefCounter();
        counter.set("e1", 1);
        SourceRefCounter.Result expected = new SourceRefCounter.Result(0, true);
        assertThat(counter.decrement("e1")).isEqualTo(expected);
    }
}

package org.synanton.bench.baseline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 028b.1 scaffold proof: module wiring only. Loader lands in 028b.2. */
class ModuleWiringTest {

    @Test
    void moduleLoads() {
        assertThat("synanton-bench-baseline").isNotEmpty();
    }
}

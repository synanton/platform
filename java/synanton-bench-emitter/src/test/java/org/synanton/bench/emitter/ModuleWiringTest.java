package org.synanton.bench.emitter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A.1 scaffold proof: module wiring only. Shared executor lands in later
 * Phase A tasks; 028c/d/e wire adapters against it.
 */
class ModuleWiringTest {

    @Test
    void moduleLoads() {
        assertThat("synanton-bench-emitter").isNotEmpty();
    }
}

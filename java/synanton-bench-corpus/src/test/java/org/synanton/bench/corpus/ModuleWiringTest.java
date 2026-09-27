package org.synanton.bench.corpus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 028a.1 scaffold proof: module wiring only. Logic lands in 028a.2+.
 * The generator is standalone: no CQL, no SQL, no adapter imports —
 * enforced here by the absence of those dependencies (this module depends
 * on Jackson + test libs only).
 */
class ModuleWiringTest {

    @Test
    void moduleLoads() {
        assertThat(ModuleInfo.NAME).isEqualTo("synanton-bench-corpus");
    }
}

package org.synanton.synflux.lifecycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documented public failure-injection trigger for #253's acceptance run:
 * the configured point selects the crash, the crash is one-shot per process,
 * and a non-matching point is a no-op.
 */
class FaultInjectionTest {

    @AfterEach
    void tearDown() {
        FaultInjection.reset();
    }

    @Test
    void matchingPointCrashesOnceWithInjectedExitCode() {
        FaultInjection.configureForTest("after-manifest");
        List<Integer> exits = new ArrayList<>();
        FaultInjection.setExitAction(exits::add);

        FaultInjection.crashAt("after-discover");
        FaultInjection.crashAt("after-manifest");
        FaultInjection.crashAt("after-manifest");

        assertThat(exits).containsExactly(FaultInjection.INJECTED_EXIT_CODE);
    }

    @Test
    void unsetOrForeignPointIsNoOp() {
        List<Integer> exits = new ArrayList<>();
        FaultInjection.setExitAction(exits::add);

        FaultInjection.crashAt("after-manifest");
        assertThat(exits).isEmpty();

        FaultInjection.configureForTest("after-acquire");
        FaultInjection.crashAt("after-manifest");
        assertThat(exits).isEmpty();
    }

    @Test
    void resetRestoresOneShotBudget() {
        FaultInjection.configureForTest("after-acquire");
        List<Integer> exits = new ArrayList<>();
        FaultInjection.setExitAction(exits::add);

        FaultInjection.crashAt("after-acquire");
        FaultInjection.reset();
        FaultInjection.configureForTest("after-acquire");
        FaultInjection.setExitAction(exits::add);
        FaultInjection.crashAt("after-acquire");

        assertThat(exits).containsExactly(FaultInjection.INJECTED_EXIT_CODE,
                FaultInjection.INJECTED_EXIT_CODE);
    }
}

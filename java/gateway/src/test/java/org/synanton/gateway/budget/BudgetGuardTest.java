package org.synanton.gateway.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetGuardTest {

    @Test
    void shouldDenyWhenCapExhausted() {
        BudgetGuard.Decision expected = new BudgetGuard.Decision(false, 86400, "monthly_usd_cap exhausted");
        assertThat(new BudgetGuard().check(100, 100)).isEqualTo(expected);
    }
}

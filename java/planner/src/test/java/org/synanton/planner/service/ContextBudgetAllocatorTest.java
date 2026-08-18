package org.synanton.planner.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetAllocatorTest {

    @Test
    void shouldAllocateSynthesisBudget() {
        ContextBudgetAllocator.Budget budget = new ContextBudgetAllocator().allocate("SYNTHESIS", 32000);
        assertThat(budget.totalTokens()).isEqualTo(32000);
        assertThat(budget.allocation().get("chunks")).isEqualTo(17600);
    }
}

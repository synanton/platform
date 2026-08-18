package org.synanton.router;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedFairSchedulerTest {

    @Test
    void shouldPreferInteractiveOverRecrawl() {
        WeightedFairScheduler scheduler = new WeightedFairScheduler();
        scheduler.enqueue(new WeightedFairScheduler.WorkItem("hot", "{}", "RECRAWL_BACKGROUND", "RECRAWL_AFTER_RESTORATION", 1000));
        scheduler.enqueue(new WeightedFairScheduler.WorkItem("steady", "{}", "INTERACTIVE", "USER_TRIGGERED", 100));
        WeightedFairScheduler.WorkItem next = scheduler.poll(Map.of("hot", 8, "steady", 8));
        assertThat(next.tenantId()).isEqualTo("steady");
        assertThat(WeightedFairScheduler.classify("USER_TRIGGERED")).isEqualTo("INTERACTIVE");
    }
}

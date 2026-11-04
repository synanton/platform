package org.synanton.eventing.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxMetricsTest {

    @Test
    void metrics_withRegistry_registersThePinnedNames() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxMetrics(registry, () -> 0L);

        assertThat(registry.get(OutboxMetrics.PENDING_COUNT)).isNotNull();
        assertThat(registry.get(OutboxMetrics.DISPATCHED_TOTAL)).isNotNull();
        assertThat(registry.get(OutboxMetrics.FAILED_TOTAL)).isNotNull();
        assertThat(registry.get(OutboxMetrics.ATTEMPTS)).isNotNull();
    }

    @Test
    void pendingGauge_readsTheSuppliedCountAndStaysCurrent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong pending = new AtomicLong(3);

        new OutboxMetrics(registry, pending::get);

        assertThat(registry.get(OutboxMetrics.PENDING_COUNT).gauge().value()).isEqualTo(3.0);
        pending.set(0);
        assertThat(registry.get(OutboxMetrics.PENDING_COUNT).gauge().value()).isZero();
    }

    @Test
    void dispatchedAndFailed_incrementCountersAndPlainValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(registry, () -> 0L);

        metrics.dispatched();
        metrics.failed();
        metrics.failed();

        assertThat(registry.get(OutboxMetrics.DISPATCHED_TOTAL).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(OutboxMetrics.FAILED_TOTAL).counter().count()).isEqualTo(2.0);
        assertThat(metrics.dispatchedCount()).isEqualTo(1);
        assertThat(metrics.failedCount()).isEqualTo(2);
    }

    @Test
    void attempt_recordsIntoTheCountOrientedSummary() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(registry, () -> 0L);

        metrics.attempt(1);
        metrics.attempt(100);

        assertThat(registry.get(OutboxMetrics.ATTEMPTS).summary().count()).isEqualTo(2);
        assertThat(registry.get(OutboxMetrics.ATTEMPTS).summary().max()).isEqualTo(100.0);
    }

    @Test
    void noOpMetrics_withoutRegistry_tracksPlainValuesOnly() {
        OutboxMetrics metrics = new OutboxMetrics();

        metrics.dispatched();
        metrics.failed();
        metrics.attempt(7);

        assertThat(metrics.dispatchedCount()).isEqualTo(1);
        assertThat(metrics.failedCount()).isEqualTo(1);
    }
}

package org.synanton.eventing.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Publication-lag observability for the outbox. The metric names are the contract
 * (#476): dashboards and alerting key off them, so no short aliases. A {@code null}
 * registry keeps the plain counters available to non-Spring callers and tests; the
 * Micrometer meters are registered only when a registry is supplied.
 */
public final class OutboxMetrics {

    public static final String PENDING_COUNT = "eventing.outbox.pending.count";
    public static final String DISPATCHED_TOTAL = "eventing.outbox.dispatched.total";
    public static final String FAILED_TOTAL = "eventing.outbox.failed.total";
    public static final String ATTEMPTS = "eventing.outbox.attempts";

    private final LongAdder dispatched = new LongAdder();
    private final LongAdder failed = new LongAdder();

    private final Counter dispatchedCounter;
    private final Counter failedCounter;
    private final DistributionSummary attempts;

    /** No-op metrics for tests and consumers without a registry. */
    public OutboxMetrics() {
        this(null, () -> 0L);
    }

    /**
     * Registers the pinned meters. The {@code pendingCount} supplier is held strongly so
     * the gauge cannot report NaN after garbage collection.
     */
    public OutboxMetrics(MeterRegistry registry, Supplier<Long> pendingCount) {
        if (registry == null) {
            dispatchedCounter = null;
            failedCounter = null;
            attempts = null;
            return;
        }
        Gauge.builder(PENDING_COUNT, pendingCount, (Supplier<Long> supplier) -> supplier.get().doubleValue())
                .strongReference(true)
                .register(registry);
        dispatchedCounter = Counter.builder(DISPATCHED_TOTAL).register(registry);
        failedCounter = Counter.builder(FAILED_TOTAL).register(registry);
        attempts = DistributionSummary.builder(ATTEMPTS)
                .serviceLevelObjectives(1, 2, 3, 5, 10, 30, 100)
                .register(registry);
    }

    public void dispatched() {
        dispatched.increment();
        if (dispatchedCounter != null) {
            dispatchedCounter.increment();
        }
    }

    public void failed() {
        failed.increment();
        if (failedCounter != null) {
            failedCounter.increment();
        }
    }

    /** Records one dispatch attempt number so a poison message is distinguishable from a transient retry. */
    public void attempt(int attemptNumber) {
        if (attempts != null) {
            attempts.record(attemptNumber);
        }
    }

    public long dispatchedCount() {
        return dispatched.sum();
    }

    public long failedCount() {
        return failed.sum();
    }
}

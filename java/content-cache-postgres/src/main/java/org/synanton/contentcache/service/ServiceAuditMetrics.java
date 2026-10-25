package org.synanton.contentcache.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.LongAdder;

/**
 * The otherwise-silent denial-audit failure path. The counter is exported as
 * {@value #DENIAL_WRITE_FAILURE} when a registry is supplied; the LongAdder
 * keeps the same value available to non-Spring callers and tests.
 */
public final class ServiceAuditMetrics {

    public static final String DENIAL_WRITE_FAILURE = "audit.denial.write.failure";

    private final LongAdder failures = new LongAdder();
    private final Counter counter;

    public ServiceAuditMetrics() {
        this(null);
    }

    public ServiceAuditMetrics(MeterRegistry registry) {
        this.counter = registry == null
                ? null
                : Counter.builder(DENIAL_WRITE_FAILURE)
                        .description("Denial audit writes that failed; the denial still returned zero rows")
                        .register(registry);
    }

    public void denialWriteFailed() {
        failures.increment();
        if (counter != null) {
            counter.increment();
        }
    }

    public long denialWriteFailures() {
        return failures.sum();
    }
}

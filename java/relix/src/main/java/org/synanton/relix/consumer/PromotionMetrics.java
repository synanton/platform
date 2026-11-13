package org.synanton.relix.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Per-source promotion outcomes and generation drift. */
final class PromotionMetrics {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicLong> lastAuthorized = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> fenceDrift = new ConcurrentHashMap<>();

    PromotionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void outcome(String source, String outcome) {
        registry.counter("promotion.outcome", "source", source(source), "outcome", outcome).increment();
    }

    void retried(String source) {
        registry.counter("promotion.retried", "source", source(source)).increment();
    }

    void exhausted(String source) {
        registry.counter("promotion.exhausted", "source", source(source)).increment();
    }

    void authorized(String source, long generation) {
        value(lastAuthorized, "promotion.last_authorized_generation", source).set(generation);
        value(fenceDrift, "promotion.fence_drift", source).set(0);
    }

    void observedDrift(String source, long attemptedGeneration, long fenceGeneration) {
        if (attemptedGeneration < 1 || fenceGeneration < 1) {
            return;
        }
        value(fenceDrift, "promotion.fence_drift", source).set(fenceGeneration - attemptedGeneration);
    }

    private AtomicLong value(ConcurrentHashMap<String, AtomicLong> values, String name, String source) {
        String key = source(source);
        return values.computeIfAbsent(key, ignored -> {
            AtomicLong value = new AtomicLong();
            registry.gauge(name, Tags.of("source", key), value, AtomicLong::get);
            return value;
        });
    }

    private static String source(String source) {
        return source == null || source.isBlank() ? UNKNOWN : source;
    }
}

package org.synanton.synflux.extraction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Bounded-cardinality extraction metrics. Tenant identity and source versions
 * stay in structured logs; metric labels are provider, tenant class and outcome.
 */
@Component
public final class ExtractionMetrics {
    private final MeterRegistry registry;
    private final Map<String, AtomicReference<Double>> acceptanceByProvider =
            new ConcurrentHashMap<>();

    public ExtractionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordExtraction(String tenantId, String provider, String outcome,
                                 long durationMs, int entities, int candidates,
                                 int accepted, int rejected) {
        String tenantClass = tenantClass(tenantId);
        Counter.builder("extraction_completed_total")
                .tags("provider", provider, "tenant_class", tenantClass, "outcome", outcome)
                .register(registry).increment();
        Timer.builder("extraction_duration_seconds")
                .tag("provider", provider).register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
        DistributionSummary.builder("extraction_entities_count")
                .tag("provider", provider).register(registry).record(entities);
        DistributionSummary.builder("extraction_relations_candidates_count")
                .tag("provider", provider).register(registry).record(candidates);
        double ratio = candidates == 0 ? 0.0 : accepted / (double) candidates;
        acceptanceByProvider.computeIfAbsent(provider, key -> {
            AtomicReference<Double> value = new AtomicReference<>(0.0);
            Gauge.builder("relation_acceptance_ratio", value, AtomicReference::get)
                    .tag("provider", key).register(registry);
            return value;
        }).set(ratio);
        if (rejected < 0) {
            throw new IllegalArgumentException("rejected must be non-negative");
        }
    }

    public void lifecycleRegression(String type, String provider) {
        Counter.builder("lifecycle_regressions_total")
                .tags("type", type, "provider", provider)
                .register(registry).increment();
    }

    public void attributionError(String errorType, String provider) {
        Counter.builder("attribution_errors_total")
                .tags("error_type", errorType, "provider", provider)
                .register(registry).increment();
    }

    public static String tenantClass(String tenantId) {
        if ("demo".equals(tenantId)) {
            return "demo";
        }
        return tenantId != null && tenantId.startsWith("eval-") ? "eval" : "other";
    }
}

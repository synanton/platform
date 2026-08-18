package org.synanton.security.idp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class IdpStatusAmortizationCache {

    public record IdpStatus(String status, Instant refreshedAt) {
        public boolean disabled() {
            return "DISABLED".equalsIgnoreCase(status);
        }
    }

    private final Map<String, Cache<String, IdpStatus>> caches = new ConcurrentHashMap<>();
    private final Map<String, Duration> ttlByTier;
    private final long maxSize;
    private final MeterRegistry meterRegistry;
    private final Function<String, IdpStatus> probe;

    public IdpStatusAmortizationCache(
            Map<String, Duration> ttlByTier,
            long maxSize,
            Function<String, IdpStatus> probe,
            MeterRegistry meterRegistry
    ) {
        this.ttlByTier = Map.copyOf(ttlByTier);
        this.maxSize = maxSize;
        this.probe = probe;
        this.meterRegistry = meterRegistry != null ? meterRegistry : Metrics.globalRegistry;
    }

    public IdpStatus getOrRefresh(String subjectId, String tenantId, String tier) {
        Cache<String, IdpStatus> cache = cacheFor(tier);
        IdpStatus cached = cache.getIfPresent(subjectId);
        if (cached != null) {
            meterRegistry.timer("security_idp_amortization_stale_seconds", "tenant", tenantId, "tier", tier)
                    .record(Duration.between(cached.refreshedAt(), Instant.now()));
            return cached;
        }
        IdpStatus fresh = probe.apply(subjectId);
        cache.put(subjectId, fresh);
        return fresh;
    }

    public IdpStatus refreshAndCompare(String subjectId, String tenantId, String tier) {
        Cache<String, IdpStatus> cache = cacheFor(tier);
        IdpStatus cached = cache.getIfPresent(subjectId);
        IdpStatus fresh = probe.apply(subjectId);
        if (cached != null && !cached.status().equals(fresh.status()) && "ACTIVE".equals(cached.status())) {
            meterRegistry.counter("security_idp_amortization_stale_authz_total", "tenant", tenantId, "tier", tier)
                    .increment();
        }
        cache.put(subjectId, fresh);
        return fresh;
    }

    public void evict(String subjectId) {
        caches.values().forEach(cache -> cache.invalidate(subjectId));
    }

    private Cache<String, IdpStatus> cacheFor(String tier) {
        Duration ttl = ttlByTier.getOrDefault(tier, Duration.ofSeconds(60));
        return caches.computeIfAbsent(tier, ignored -> Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build());
    }
}

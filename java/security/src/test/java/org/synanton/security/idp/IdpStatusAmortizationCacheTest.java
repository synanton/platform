package org.synanton.security.idp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IdpStatusAmortizationCacheTest {

    @Test
    void shouldServeCachedStatusWithinTtl() {
        AtomicInteger probes = new AtomicInteger();
        IdpStatusAmortizationCache cache = new IdpStatusAmortizationCache(
                Map.of("HIGH_SECURITY", Duration.ofSeconds(5)),
                100,
                subject -> {
                    probes.incrementAndGet();
                    return new IdpStatusAmortizationCache.IdpStatus("ACTIVE", Instant.now());
                },
                new SimpleMeterRegistry()
        );
        cache.getOrRefresh("user:alice", "demo", "HIGH_SECURITY");
        cache.getOrRefresh("user:alice", "demo", "HIGH_SECURITY");
        assertThat(probes.get()).isEqualTo(1);
    }

    @Test
    void shouldEvictOnScimDisable() {
        AtomicInteger probes = new AtomicInteger();
        IdpStatusAmortizationCache cache = new IdpStatusAmortizationCache(
                Map.of("STANDARD", Duration.ofSeconds(60)),
                100,
                subject -> {
                    int n = probes.incrementAndGet();
                    return new IdpStatusAmortizationCache.IdpStatus(n == 1 ? "ACTIVE" : "DISABLED", Instant.now());
                },
                new SimpleMeterRegistry()
        );
        assertThat(cache.getOrRefresh("user:alice", "demo", "STANDARD").status()).isEqualTo("ACTIVE");
        cache.evict("user:alice");
        assertThat(cache.getOrRefresh("user:alice", "demo", "STANDARD").status()).isEqualTo("DISABLED");
    }
}

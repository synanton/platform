package org.synanton.storage.contract;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory metrics implementation (dev/test default). A Micrometer-backed
 * implementation replaces this in deployments; the taxonomy (§8.3) does not change.
 */
public final class InMemoryAdapterMetrics implements AdapterMetrics {

    private final String providerId;
    private final ConcurrentHashMap<String, OpStats> operations = new ConcurrentHashMap<>();

    public InMemoryAdapterMetrics(String providerId) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
    }

    @Override
    public void record(String operation, long latencyNanos, boolean success) {
        operations.computeIfAbsent(operation, op -> new OpStats()).record(latencyNanos, success);
    }

    @Override
    public AdapterStats snapshot() {
        Map<String, OpStats> copy = new HashMap<>(operations);
        return new AdapterStats(providerId, copy);
    }
}

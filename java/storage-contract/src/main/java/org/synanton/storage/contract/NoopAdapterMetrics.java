package org.synanton.storage.contract;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** No-op metrics (adapter default until a deployment injects a real implementation). */
public final class NoopAdapterMetrics implements AdapterMetrics {

    public static final NoopAdapterMetrics INSTANCE = new NoopAdapterMetrics();

    private NoopAdapterMetrics() {}

    @Override
    public void record(String operation, long latencyNanos, boolean success) {}

    @Override
    public AdapterStats snapshot() {
        return new AdapterStats("noop@0", Map.of());
    }
}

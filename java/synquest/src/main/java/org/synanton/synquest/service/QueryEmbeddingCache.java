package org.synanton.synquest.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory LRU cache of query vectors, keyed by (tenant, logical model, query text).
 * Re-running the same benchmark queries doesn't re-pay GPU-plane requests (free-tier quota,
 * retrieval benchmark plan §6 Phase B1-G, G4).
 *
 * <ul>
 *   <li>Off by default ({@code synquest.embedding.query-cache-size=0}); the gpu-plane profile
 *       enables it.</li>
 *   <li>The key includes the tenant, so a cached vector never skips the GPU plane's per-tenant
 *       authorization for a tenant that hasn't been authorized yet.</li>
 *   <li>Vectors are stored after {@link EmbeddingShape#fit}. Dimension settings only change on
 *       restart, which empties the cache.</li>
 *   <li>Only successful embeddings are cached; failures are never cached.</li>
 * </ul>
 */
@Component
public class QueryEmbeddingCache {

    private final int maxEntries;
    private final Map<String, float[]> entries;

    public QueryEmbeddingCache(@Value("${synquest.embedding.query-cache-size:0}") int maxEntries) {
        this.maxEntries = Math.max(0, maxEntries);
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > QueryEmbeddingCache.this.maxEntries;
            }
        };
    }

    public boolean enabled() {
        return maxEntries > 0;
    }

    public synchronized Optional<float[]> get(String tenant, String model, String query) {
        if (!enabled()) {
            return Optional.empty();
        }
        float[] v = entries.get(key(tenant, model, query));
        return v == null ? Optional.empty() : Optional.of(v.clone());
    }

    public synchronized void put(String tenant, String model, String query, float[] vector) {
        if (enabled()) {
            entries.put(key(tenant, model, query), vector.clone());
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    private static String key(String tenant, String model, String query) {
        return (tenant == null ? "" : tenant) + '\u0000' + model + '\u0000' + query;
    }
}

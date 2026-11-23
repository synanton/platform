package org.synanton.extraction.engine.provenance;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal cache boundary for the #844 identity/non-activation tests. */
public final class ExtractionCache {

    private final ConcurrentHashMap<ExtractionCacheKey, CachedExtraction> entries =
            new ConcurrentHashMap<>();

    public Optional<CachedExtraction> get(ExtractionCacheKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    public void put(ExtractionCacheKey key, CachedExtraction value) {
        entries.put(key, value);
    }

    public int size() {
        return entries.size();
    }
}

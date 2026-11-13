package org.synanton.relix.adapter.out.metadata;

import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.relix.identity.SourceFenceKey;
import org.synanton.relix.identity.SourceGenerationAuthority;

import java.util.Optional;

public final class CassandraSourceGenerationAuthority implements SourceGenerationAuthority {

    private final IngestionCacheClient cache;

    public CassandraSourceGenerationAuthority(IngestionCacheClient cache) {
        this.cache = cache;
    }

    @Override
    public Optional<Long> acceptedGeneration(SourceFenceKey key) {
        return cache.readAcceptedGeneration(key.tenantId(), key.sourceId());
    }
}

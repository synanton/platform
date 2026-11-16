package org.synanton.synflux.extraction.provider;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.client.IngestionCacheClient;

/** Read path for per-tenant provider overrides. */
@Component
public final class ProviderConfigRepository {
    private final IngestionCacheClient cacheClient;

    @Autowired
    public ProviderConfigRepository(IngestionCacheClient cacheClient) {
        this.cacheClient = cacheClient;
    }

    public Optional<String> findByTenant(String tenantId) {
        return cacheClient.readTenantProvider(tenantId);
    }
}

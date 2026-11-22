package org.synanton.relix.provisioning;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import java.time.Instant;
import java.util.Optional;
import org.synanton.ingestioncache.client.IngestionCacheClient;

/** Cassandra-backed registry/provider/empty-state store. */
public final class CassandraProvisioningStore implements ProvisioningStore {
    private final CqlSession session;
    private final IngestionCacheClient cache;

    public CassandraProvisioningStore(CqlSession session, IngestionCacheClient cache) {
        this.session = session;
        this.cache = cache;
    }

    @Override
    public boolean isRegistered(String tenantId) {
        return cache.listTenantIds().contains(tenantId);
    }

    @Override
    public Optional<String> provider(String tenantId) {
        return cache.readTenantProvider(tenantId);
    }

    @Override
    public void setProvider(String tenantId, String provider, Instant now) {
        cache.setTenantProvider(tenantId, provider, now);
    }

    @Override
    public long sourceVersionCount(String tenantId) {
        Row row = session.execute(
                "SELECT count(*) AS c FROM ingestion_cache.source_versions " +
                        "WHERE tenant_id=? ALLOW FILTERING", tenantId).one();
        return row == null ? 0 : row.getLong("c");
    }

    @Override
    public void registerTenant(String tenantId, Instant now) {
        cache.registerTenant(tenantId, now);
    }
}

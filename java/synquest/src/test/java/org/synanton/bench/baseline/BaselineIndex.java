package org.synanton.bench.baseline;

import java.io.Closeable;
import java.util.Map;
import org.synanton.synquest.service.HybridSearcher;

/**
 * Per-tenant Lucene index set (028b.3 builds all 50; 028b.1b proves the shape
 * on a mini index). Tenant isolation is structural: disjoint directories,
 * never a shared index with a tenant filter.
 */
public final class BaselineIndex {

    public record TenantIndex(Map<String, HybridSearcher> byTenant) implements Closeable {
        public HybridSearcher searcher(String tenantId) {
            HybridSearcher searcher = byTenant.get(tenantId);
            if (searcher == null) {
                throw new IllegalArgumentException("no baseline index for tenant: " + tenantId);
            }
            return searcher;
        }

        @Override
        public void close() {
            byTenant.values().forEach(s -> {
                try {
                    s.close();
                } catch (Exception e) {
                    System.err.println("WARN: baseline searcher close failed: " + e.getMessage());
                }
            });
        }
    }

    private BaselineIndex() {}
}

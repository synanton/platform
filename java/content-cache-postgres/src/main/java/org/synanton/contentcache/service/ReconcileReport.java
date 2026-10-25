package org.synanton.contentcache.service;

import java.util.Map;
import org.synanton.storage.contract.TenantScope;

/** Per-tenant row counts; no data is included for tenants outside the set. */
public record ReconcileReport(Map<TenantScope, Integer> rowCounts) {

    public ReconcileReport {
        rowCounts = Map.copyOf(rowCounts);
    }

    public static ReconcileReport empty() {
        return new ReconcileReport(Map.of());
    }

    public int count(TenantScope scope) {
        return rowCounts.getOrDefault(scope, 0);
    }
}

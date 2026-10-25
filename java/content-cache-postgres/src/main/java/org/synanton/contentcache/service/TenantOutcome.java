package org.synanton.contentcache.service;

/** Per-tenant reconcile result carried by a partial failure. */
public record TenantOutcome(int rowCount, String failure) {

    public static TenantOutcome success(int rowCount) {
        return new TenantOutcome(rowCount, null);
    }

    public static TenantOutcome failure(String failure) {
        return new TenantOutcome(0, failure == null || failure.isBlank() ? "failed" : failure);
    }

    public boolean success() {
        return failure == null;
    }
}

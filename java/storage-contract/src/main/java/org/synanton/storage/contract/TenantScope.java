package org.synanton.storage.contract;

/**
 * Tenant scope derived from a validated identity context (Identity 1.29 + Security 1.23).
 *
 * <p>Single source of truth for tenant identity across {@code synvault-api} and
 * {@code synquest-api}. Storage-level tenant keys in adapters are an implementation
 * mechanism and must always be derived from this validated scope — never the reverse.
 *
 * @param tenantId validated tenant identifier; never blank
 */
public record TenantScope(String tenantId) {
    public TenantScope {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }

    public static TenantScope of(String tenantId) {
        return new TenantScope(tenantId);
    }
}

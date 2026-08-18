package org.synanton.synt.infra.security;

public final class TenantContext {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        TENANT.set(tenantId);
    }

    public static String getTenantId() {
        return TENANT.get();
    }

    public static void clear() {
        TENANT.remove();
    }
}

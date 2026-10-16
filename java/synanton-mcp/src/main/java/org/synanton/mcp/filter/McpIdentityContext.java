package org.synanton.mcp.filter;

/**
 * Request-scoped holder for the verified MCP identity. The tenant is bound
 * from the validated security-provider response only — never read from an
 * inbound header, never defaulted. Named to avoid clashing with the shared
 * {@code org.synanton.common.tenant.TenantContext}, which is shaped around
 * {@code SubjectAssertion} (uid/gids/roles) and does not fit this identity.
 */
public final class McpIdentityContext {
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private McpIdentityContext() {}

    public static void set(Identity id) {
        CURRENT.set(id);
    }

    public static Identity get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    public record Identity(String tenantId, String subjectId, java.util.List<String> scopes) {
        public Identity {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId required");
            }
            scopes = scopes == null ? java.util.List.of() : java.util.List.copyOf(scopes);
        }
    }
}

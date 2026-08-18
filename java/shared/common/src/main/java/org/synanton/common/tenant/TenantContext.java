package org.synanton.common.tenant;

import org.synanton.common.jwt.SubjectAssertion;

import java.util.List;
import java.util.Set;

/**
 * Thread-local holder for the authenticated request context.
 * Populated by TenantContextFilter at the REST boundary.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    private final String tenantId;
    private final String subject;
    private final int uid;
    private final List<Integer> gids;
    private final Set<String> roles;

    private TenantContext(String tenantId, String subject, int uid, List<Integer> gids, Set<String> roles) {
        this.tenantId = tenantId;
        this.subject = subject;
        this.uid = uid;
        this.gids = List.copyOf(gids);
        this.roles = Set.copyOf(roles);
    }

    public static void set(SubjectAssertion assertion) {
        HOLDER.set(new TenantContext(
                assertion.tenantId(),
                assertion.subject(),
                assertion.uid(),
                assertion.gids(),
                assertion.roles()
        ));
    }

    /** Set an anonymous context (tenant known, no authenticated user). */
    public static void setAnonymous(String tenantId) {
        HOLDER.set(new TenantContext(tenantId, null, -1, List.of(), Set.of()));
    }

    public static TenantContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static boolean isAuthenticated() {
        TenantContext ctx = HOLDER.get();
        return ctx != null && ctx.subject != null;
    }

    public String tenantId() { return tenantId; }
    public String subject() { return subject; }
    public int uid() { return uid; }
    public List<Integer> gids() { return gids; }
    public Set<String> roles() { return roles; }
    public boolean hasRole(String role) { return roles.contains(role); }
}

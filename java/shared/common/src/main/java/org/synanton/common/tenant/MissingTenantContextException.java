package org.synanton.common.tenant;

/**
 * Thrown when a request reaches a tenant-scoped operation without a verified
 * tenant context.
 *
 * <p>This is the fail-closed replacement for the demo-era {@code ... : "demo"}
 * fallbacks: a missing tenant must reject, never silently resolve to a demo
 * tenant. Callers must present a verified identity (JWT/OIDC/API key) — or, in
 * demo tooling, an explicit tenant header through the opt-in mock filter.
 *
 * <p>One shared class, no subclasses: every site describes the same failure.
 * If a module ever needs to distinguish itself, put the module name in the
 * message, not in a type.
 */
@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
public class MissingTenantContextException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MissingTenantContextException() {
        super("tenant context missing; a verified identity must set it");
    }

    public MissingTenantContextException(String detail) {
        super("tenant context missing: " + detail);
    }
}

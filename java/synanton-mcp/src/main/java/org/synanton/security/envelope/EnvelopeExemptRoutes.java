package org.synanton.security.envelope;

import java.util.Locale;
import java.util.Set;

/**
 * Explicit method-and-path allowlist for envelope-exempt routes.
 *
 * <p>An exemption removes the tenant-envelope requirement ONLY. Any
 * authentication or authorization the route already performs remains in
 * force. Exemption is not a bypass.
 *
 * <p>Exemption is exact: no prefix matching, no normalization. Callers
 * must supply the framework-resolved application-relative path (no query
 * string, no path parameters normalized differently than the router did).
 * A second normalization scheme inside the security filter is a
 * divergence source.
 *
 * <p>{@code GET /mcp} is deliberately NOT in this list. The MCP auth
 * filter owns the capabilities exemption and must distinguish capabilities
 * from business operations. Business requests to {@code /mcp} are not
 * exempt.
 */
public final class EnvelopeExemptRoutes {

    /**
     * Route key for exact method/path matching. Package-private: the class
     * is an internal value type, not part of the public classifier API.
     */
    record RouteKey(String method, String path) {
        RouteKey {
            if (method == null || method.isBlank())
                throw new IllegalArgumentException("method required");
            if (path == null || !path.startsWith("/"))
                throw new IllegalArgumentException("path must be application-relative and absolute");
            method = method.toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Enumerate only the operational endpoints actually enabled in the
     * service. Do not add a path because it "starts with /actuator" — the
     * Actuator surface is service-configurable, and an over-broad
     * exemption removes envelope checks from a business route.
     */
    private static final Set<RouteKey> EXEMPT = Set.of(
        new RouteKey("GET", "/health"),
        new RouteKey("GET", "/readyz"),
        new RouteKey("GET", "/livez"),
        new RouteKey("GET", "/metrics"),
        new RouteKey("GET", "/actuator/health"),
        new RouteKey("GET", "/actuator/info"),
        new RouteKey("GET", "/actuator/prometheus")
    );

    private EnvelopeExemptRoutes() {}

    /**
     * Classify a request.
     *
     * @param method           HTTP method, any case; blank returns false
     * @param applicationPath  application-relative path from the framework's
     *                         mapping conventions; not normalized here
     * @return true only if the method/path pair is in the enumerated allowlist
     */
    public static boolean isExempt(String method, String applicationPath) {
        if (method == null || method.isBlank()) return false;
        if (applicationPath == null || applicationPath.isBlank()) return false;
        return EXEMPT.contains(new RouteKey(method, applicationPath));
    }

    /** Package-private: exposed for tests that verify allowlist parity. */
    static Set<RouteKey> enumerated() {
        return EXEMPT;
    }
}

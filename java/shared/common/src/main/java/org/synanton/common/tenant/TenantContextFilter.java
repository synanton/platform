package org.synanton.common.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.synanton.common.context.RequestContext;
import org.synanton.common.context.RequestContextHolder;
import org.synanton.common.error.AuthException;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.jwt.SubjectAssertion;

import java.io.IOException;
import java.util.UUID;

/**
 * Spring-compatible servlet filter that populates TenantContext from a Bearer JWT.
 *
 * Paths in the skip-list (e.g. /auth/**, /actuator/**) are passed through
 * without authentication. All other paths require a valid Bearer token.
 */
public class TenantContextFilter extends org.springframework.web.filter.OncePerRequestFilter {

    private final JwtVerifier jwtVerifier;
    private final String defaultTenantId;
    private final String[] skipPrefixes;

    public TenantContextFilter(JwtVerifier jwtVerifier, String defaultTenantId, String... skipPrefixes) {
        this.jwtVerifier = jwtVerifier;
        this.defaultTenantId = defaultTenantId;
        this.skipPrefixes = skipPrefixes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();

        for (String prefix : skipPrefixes) {
            if (path.startsWith(prefix)) {
                TenantContext.setAnonymous(defaultTenantId);
                String traceId = resolveTraceId(request);
                RequestContextHolder.set(new RequestContext(defaultTenantId, "anonymous", traceId));
                try {
                    chain.doFilter(request, response);
                } finally {
                    TenantContext.clear();
                    RequestContextHolder.clear();
                }
                return;
            }
        }

        String authHeader = request.getHeader("Authorization");
        // API keys (syn_ prefix) are not JWT - skip JWT verification in this filter;
        // services that need full API-key resolution call security directly.
        if (authHeader != null && authHeader.startsWith("Bearer syn_")) {
            String traceId = resolveTraceId(request);
            RequestContextHolder.set(new RequestContext(defaultTenantId, "api-key-caller", traceId));
            try {
                chain.doFilter(request, response);
            } finally {
                RequestContextHolder.clear();
            }
            return;
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":401,\"code\":\"ERR_AUTH_FAILED\",\"detail\":\"Missing Bearer token\"}");
            return;
        }

        try {
            SubjectAssertion assertion = jwtVerifier.verify(authHeader.substring(7));
            TenantContext.set(assertion);
            String traceId = resolveTraceId(request);
            RequestContextHolder.set(new RequestContext(assertion.tenantId(), assertion.subject(), traceId));
            chain.doFilter(request, response);
        } catch (AuthException e) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":401,\"code\":\"ERR_AUTH_FAILED\",\"detail\":\"" + e.getMessage() + "\"}");
        } finally {
            TenantContext.clear();
            RequestContextHolder.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
    }
}

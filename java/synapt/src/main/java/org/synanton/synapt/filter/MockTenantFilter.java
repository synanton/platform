package org.synanton.synapt.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.synanton.common.tenant.TenantContext;

import java.io.IOException;
import java.util.UUID;

/**
 * Demo-plane tenant mock — explicitly opt-in ({@code synanton.mock-tenant.enabled=true}),
 * off by default. It reads the inbound X-Tenant header (a propagation-contract
 * violation, demo tooling only) and used to default every request to the
 * hardcoded "demo" tenant: a boot whose component scan picked this filter up
 * silently replaced the verified JWT context with demo/anonymous before the
 * controller saw it (M70q run-1 401: "Verified tenant context is required").
 * When enabled, a request without X-Tenant is rejected — fail closed, never a
 * baked-in tenant from absence.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "synanton.mock-tenant.enabled", havingValue = "true")
@Component
public class MockTenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String tenant = request.getHeader("X-Tenant");
        if (tenant == null || tenant.isBlank()) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":401,\"code\":\"ERR_AUTH_FAILED\",\"detail\":\"Missing X-Tenant header\"}");
            return;
        }

        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString();

        TenantContext.setAnonymous(tenant);
        MDC.put("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("traceId");
        }
    }
}

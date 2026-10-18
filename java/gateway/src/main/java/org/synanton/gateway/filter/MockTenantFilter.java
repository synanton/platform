package org.synanton.gateway.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;

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
public class MockTenantFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String tenant = ((HttpServletRequest) req).getHeader("X-Tenant");

        if (tenant == null || tenant.isBlank()) {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401);
            ((jakarta.servlet.http.HttpServletResponse) res).setContentType("application/json");
            ((jakarta.servlet.http.HttpServletResponse) res).getWriter().write(
                    "{\"status\":401,\"code\":\"ERR_AUTH_FAILED\",\"detail\":\"Missing X-Tenant header\"}");
            return;
        }
        req.setAttribute("tenant", tenant);
        chain.doFilter(req, res);
    }
}

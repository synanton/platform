package org.synanton.synvault.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.synanton.common.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class MockTenantFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String tenant = ((HttpServletRequest) req).getHeader("X-Tenant");
        TenantContext.setAnonymous(tenant != null ? tenant : "demo");
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}

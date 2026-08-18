package org.synanton.synflux.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class MockTenantFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        String tenant = ((HttpServletRequest) req).getHeader("X-Tenant");
        if (tenant == null || tenant.isBlank()) tenant = "demo";
        req.setAttribute("tenant", tenant);
        chain.doFilter(req, res);
    }
}

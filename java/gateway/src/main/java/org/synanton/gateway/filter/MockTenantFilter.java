package org.synanton.gateway.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class MockTenantFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String tenant = ((HttpServletRequest) req).getHeader("X-Tenant");
        if (tenant == null || tenant.isBlank()) tenant = "demo";
        req.setAttribute("tenant", tenant);
        chain.doFilter(req, res);
    }
}

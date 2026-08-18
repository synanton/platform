package org.synanton.synt.infra.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MockTenantFilter extends OncePerRequestFilter {

    private final String defaultTenant;

    public MockTenantFilter(@Value("${syntology.tenant.default-id}") String defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String tenant = request.getHeader("X-Tenant-ID");
        if (tenant == null || tenant.isBlank()) {
            tenant = defaultTenant;
        }
        TenantContext.setTenantId(tenant);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}

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

@Component
public class MockTenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String tenant = request.getHeader("X-Tenant");
        if (tenant == null || tenant.isBlank()) tenant = "demo";

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

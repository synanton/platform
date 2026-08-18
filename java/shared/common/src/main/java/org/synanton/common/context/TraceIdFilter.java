package org.synanton.common.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that propagates or generates a trace ID for each request.
 *
 * <p>If the incoming request contains an {@code X-Trace-Id} header, that value
 * is reused. Otherwise a random UUID is generated. The resolved trace ID is
 * echoed back in the {@code X-Trace-Id} response header and made available via
 * the request attribute {@code synanton.traceId} for downstream filters
 * (e.g. {@code TenantContextFilter}) to consume.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTRIBUTE = "synanton.traceId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        chain.doFilter(request, response);
    }
}

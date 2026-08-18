package org.synanton.synapt.filter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.synanton.synapt.deprecation.DeprecatedField;

import java.io.IOException;
import java.lang.reflect.RecordComponent;

public class DeprecationWarningFilter extends OncePerRequestFilter {

    private final MeterRegistry meterRegistry;

    public DeprecationWarningFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry != null ? meterRegistry : Metrics.globalRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object body = request.getAttribute("org.synanton.deprecated.request");
        if (body != null) {
            emit(body, response);
        }
        chain.doFilter(request, response);
        Object again = request.getAttribute("org.synanton.deprecated.request");
        if (again != null) {
            emit(again, response);
        }
    }

    public void emit(Object dto, HttpServletResponse response) {
        if (dto == null || !dto.getClass().isRecord()) {
            return;
        }
        for (RecordComponent component : dto.getClass().getRecordComponents()) {
            DeprecatedField marker = component.getAnnotation(DeprecatedField.class);
            if (marker == null) {
                continue;
            }
            try {
                Object value = component.getAccessor().invoke(dto);
                if (value == null) {
                    continue;
                }
                String warning = "299 - \"field '" + component.getName() + "' is deprecated since "
                        + marker.since() + "; use '" + marker.replacement() + "'; removal earliest "
                        + marker.removalEarliest() + "\"";
                response.addHeader("Warning", warning);
                meterRegistry.counter("synapt_deprecated_field_usage_total", "field", component.getName())
                        .increment();
            } catch (Exception ignored) {
                // best-effort deprecation signalling
            }
        }
    }
}

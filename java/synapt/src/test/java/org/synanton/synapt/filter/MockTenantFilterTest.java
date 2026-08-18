package org.synanton.synapt.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.synanton.common.tenant.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MockTenantFilterTest {

    private final MockTenantFilter filter = new MockTenantFilter();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void setsTenantContextFromHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant", "acme");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);
        // TenantContext is cleared after doFilter; check it was set during chain via a capturing chain
    }

    @Test
    void defaultsToDemoWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        final String[] capturedTenant = new String[1];
        FilterChain chain = (req, res) -> capturedTenant[0] = TenantContext.get().tenantId();

        filter.doFilterInternal(request, response, chain);

        assertThat(capturedTenant[0]).isEqualTo("demo");
    }

    @Test
    void propagatesXTenantHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant", "globex");
        MockHttpServletResponse response = new MockHttpServletResponse();

        final String[] capturedTenant = new String[1];
        FilterChain chain = (req, res) -> capturedTenant[0] = TenantContext.get().tenantId();

        filter.doFilterInternal(request, response, chain);

        assertThat(capturedTenant[0]).isEqualTo("globex");
    }

    @Test
    void generatesFreshTraceIdWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void propagatesExistingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-123");
    }

    @Test
    void clearsTenantContextAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(TenantContext.get()).isNull();
    }
}

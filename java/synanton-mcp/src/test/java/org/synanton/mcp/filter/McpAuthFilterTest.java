package org.synanton.mcp.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.synanton.mcp.app.McpProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpAuthFilterTest {

    private final SecurityProviderClient provider = mock(SecurityProviderClient.class);
    private final McpProperties props = new McpProperties(
            "http://localhost:1", null, null, null, 5000,
            new McpProperties.AuthCache(60, 5000, 10L));

    private McpAuthFilter filter() {
        return new McpAuthFilter(provider, props);
    }

    private MockHttpServletRequest requestWithBearer(String bearer) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + bearer);
        return request;
    }

    @AfterEach
    void cleanup() {
        McpIdentityContext.clear();
    }

    // 5xx / transport is never cached and fails closed with 503 (not 401).
    @Test
    void upstream5xx_notCached_returns503() throws Exception {
        when(provider.validate(anyString()))
                .thenReturn(new SecurityProviderClient.Result.Upstream("status 503"));
        FilterChain chain = mock(FilterChain.class);

        McpAuthFilter filter = filter();
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilterInternal(requestWithBearer("tok-a"), first, chain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilterInternal(requestWithBearer("tok-a"), second, chain);

        assertThat(first.getStatus()).isEqualTo(503);
        assertThat(second.getStatus()).isEqualTo(503);
        assertThat(first.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE");
        verify(provider, times(2)).validate(anyString());
        verify(chain, times(0)).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // Invalid tokens are negatively cached: provider hit once, 401 every time.
    @Test
    void invalidToken_negativeCached_returns401() throws Exception {
        when(provider.validate(anyString()))
                .thenReturn(new SecurityProviderClient.Result.Invalid("rejected"));
        FilterChain chain = mock(FilterChain.class);

        McpAuthFilter filter = filter();
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilterInternal(requestWithBearer("tok-bad"), first, chain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilterInternal(requestWithBearer("tok-bad"), second, chain);

        assertThat(first.getStatus()).isEqualTo(401);
        assertThat(second.getStatus()).isEqualTo(401);
        assertThat(first.getContentAsString()).contains("AUTHENTICATION_REQUIRED");
        verify(provider, times(1)).validate(anyString());
    }

    // Valid identities are positively cached: second call never reaches the provider.
    @Test
    void validToken_positiveCached_servedFromCache() throws Exception {
        McpIdentityContext.Identity id =
                new McpIdentityContext.Identity("t-real", "sub-1", List.of("search"));
        when(provider.validate(anyString())).thenReturn(new SecurityProviderClient.Result.Ok(id));
        FilterChain chain = mock(FilterChain.class);

        McpAuthFilter filter = filter();
        filter.doFilterInternal(requestWithBearer("tok-ok"),
                new MockHttpServletResponse(), chain);
        filter.doFilterInternal(requestWithBearer("tok-ok"),
                new MockHttpServletResponse(), chain);

        verify(provider, times(1)).validate(anyString());
        verify(chain, times(2)).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // Tenant comes from the verified identity; a forged header is ignored.
    // (Design test 5 — the OWASP-load-bearing one.)
    @Test
    void tenantBound_fromVerifiedIdentity_notHeader() throws Exception {
        McpIdentityContext.Identity id =
                new McpIdentityContext.Identity("t-real", "sub-1", List.of("search"));
        when(provider.validate(anyString())).thenReturn(new SecurityProviderClient.Result.Ok(id));

        MockHttpServletRequest request = requestWithBearer("tok-tenant");
        request.addHeader("X-Tenant-Id", "forged");
        final String[] boundTenant = new String[1];
        final String[] nodeTenant = new String[1];
        FilterChain chain = (req, res) -> {
            boundTenant[0] = McpIdentityContext.get().tenantId();
            nodeTenant[0] = ((com.fasterxml.jackson.databind.JsonNode)
                    ((jakarta.servlet.http.HttpServletRequest) req)
                            .getAttribute("mcpIdentity")).path("tenantId").asText();
        };

        filter().doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(boundTenant[0]).isEqualTo("t-real");
        assertThat(nodeTenant[0]).isEqualTo("t-real");
    }

    // Missing credentials are rejected without touching the provider.
    @Test
    void missingBearer_rejectedWithoutProviderCall() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilterInternal(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        verify(provider, times(0)).validate(anyString());
    }
}

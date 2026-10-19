package org.synanton.mcp.api;

import org.junit.jupiter.api.Test;
import org.synanton.mcp.filter.McpIdentityContext;
import org.synanton.mcp.tool.ScopeEnforcer;
import org.synanton.mcp.tool.ToolRegistry;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Defense in depth: the auth filter always sets a tenant-bearing identity for
 * POST /mcp, and the identity record rejects blank tenants at construction.
 * The endpoint's former `asText("demo")` fallback was dead code in practice —
 * removed for the class, and this pins the invariant.
 */
class McpEndpointTenantRequiredTest {

    private final McpEndpoint endpoint = new McpEndpoint(mock(ToolRegistry.class), mock(ScopeEnforcer.class));

    @Test
    void requestWithoutIdentityRejects() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        assertThatThrownBy(() -> endpoint.handle("{}", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void identityRecordRejectsBlankTenant() {
        assertThatThrownBy(() -> new McpIdentityContext.Identity("  ", "sub", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }
}

package org.synanton.mcp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.synanton.mcp.tool.McpContent;
import org.synanton.mcp.tool.ScopeEnforcer;
import org.synanton.mcp.tool.ToolHandler;
import org.synanton.mcp.tool.ToolRegistry;

@ExtendWith(MockitoExtension.class)
class McpEndpointScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock ToolRegistry registry;
    @Mock ToolHandler handler;

    private McpEndpoint endpoint;

    @BeforeEach
    void setUp() {
        endpoint = new McpEndpoint(registry, new ScopeEnforcer());
        when(registry.find(ArgumentMatchers.anyString())).thenReturn(Optional.of(handler));
    }

    // Crit.1 bullet 1 — empty scope set → denied (the fail-open shape)
    @Test
    void toolsCall_emptyScopeSet_denied() throws Exception {
        Map<String, Object> result = call("[]", "search");
        assertThat(result.get("isError")).isEqualTo(Boolean.TRUE);
        verify(handler, never()).invoke(any(), any(), any());
    }

    // Crit.1 bullet 2 — unparseable scopes (string, not array) → denied
    @Test
    void toolsCall_unparseableScopes_denied() throws Exception {
        Map<String, Object> result = call("\"search\"", "search");
        assertThat(result.get("isError")).isEqualTo(Boolean.TRUE);
        verify(handler, never()).invoke(any(), any(), any());
    }

    // Crit.1 bullet 3 — insufficient scope → denied
    @Test
    void toolsCall_insufficientScope_denied() throws Exception {
        Map<String, Object> result = call("[\"tenant:list\"]", "search");
        assertThat(result.get("isError")).isEqualTo(Boolean.TRUE);
        verify(handler, never()).invoke(any(), any(), any());
    }

    // Crit.1 bullet 4a — scope for tool A used against tool B → denied for B
    @Test
    void toolsCall_scopeForToolA_deniedForToolB() throws Exception {
        Map<String, Object> result = call("[\"search\"]", "graph_query");
        assertThat(result.get("isError")).isEqualTo(Boolean.TRUE);
        verify(handler, never()).invoke(any(), any(), any());
    }

    // Crit.1 bullet 4b — positive control: valid scope for tool A permits A
    @Test
    void toolsCall_scopeForToolA_permittedForToolA() throws Exception {
        when(handler.invoke(any(), any(), any())).thenReturn(new McpContent("ok"));
        Map<String, Object> result = call("[\"search\"]", "search");
        assertThat(result.get("isError")).isEqualTo(Boolean.FALSE);
        verify(handler).invoke(any(), any(), any());
    }

    // 2d-i — endpoint catch: generic message with request_id, no exception text
    @Test
    @SuppressWarnings("unchecked")
    void toolsCall_handlerThrows_clientSeesCorrelationIdNotInternals() throws Exception {
        when(handler.invoke(any(), any(), any())).thenThrow(new RuntimeException("s3cr3t-internals"));
        Map<String, Object> result = call("[\"search\"]", "search");
        assertThat(result.get("isError")).isEqualTo(Boolean.TRUE);
        java.util.List<Map<String, Object>> content =
                (java.util.List<Map<String, Object>>) result.get("content");
        String text = (String) content.get(0).get("text");
        assertThat(text).contains("request_id:");
        assertThat(text).doesNotContain("s3cr3t-internals");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String scopesJson, String toolName) throws Exception {
        JsonNode identity = MAPPER.readTree("{\"scopes\":" + scopesJson + "}");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("mcpIdentity", identity);
        String body = "{\"id\":\"1\",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + toolName + "\",\"arguments\":{}}}";
        var entity = endpoint.handle(body, request);
        return (Map<String, Object>) entity.getBody().get("result");
    }
}

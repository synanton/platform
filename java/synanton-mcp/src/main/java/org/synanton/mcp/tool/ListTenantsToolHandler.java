package org.synanton.mcp.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ListTenantsToolHandler implements ToolHandler {
    @Override public String toolName() { return "list_tenants"; }
    @Override public String description() { return "Enumerate accessible tenants for caller."; }
    @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }
    @Override
    public McpContent invoke(Map<String, Object> arguments, String tenantId, String authHeader) {
        return new McpContent("[{\"tenant_id\":\"" + tenantId + "\"}]");
    }
}

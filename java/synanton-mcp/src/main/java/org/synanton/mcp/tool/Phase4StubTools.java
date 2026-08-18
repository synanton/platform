package org.synanton.mcp.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class Phase4StubTools implements ToolHandler {

    @Override
    public String toolName() {
        return "usage_summary";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String description() {
        return "Per-tenant usage and budget snapshot.";
    }

    @Override
    public McpContent invoke(Map<String, Object> arguments, String tenantId, String authHeader) {
        return new McpContent("tenant=" + tenantId + " budget remaining unknown");
    }
}

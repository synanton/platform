package org.synanton.mcp.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SynthesiseToolHandler implements ToolHandler {
    @Override public String toolName() { return "synthesise"; }
    @Override public String description() { return "Explicit synthesis-only over provided hits."; }
    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));
    }
    @Override
    public McpContent invoke(Map<String, Object> arguments, String tenantId, String authHeader) {
        return new McpContent("synthesis deferred to gateway for tenant " + tenantId);
    }
}

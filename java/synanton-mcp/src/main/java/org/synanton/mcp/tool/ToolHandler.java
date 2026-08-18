package org.synanton.mcp.tool;

import java.util.Map;

public interface ToolHandler {
    String toolName();
    Map<String, Object> inputSchema();
    String description();
    McpContent invoke(Map<String, Object> arguments, String tenantId, String authHeader);
}

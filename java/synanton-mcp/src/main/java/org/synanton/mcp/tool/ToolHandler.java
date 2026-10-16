package org.synanton.mcp.tool;

import java.util.Map;

public interface ToolHandler {
    String toolName();
    Map<String, Object> inputSchema();
    String description();
    ToolResult invoke(Map<String, Object> arguments, String tenantId);
}

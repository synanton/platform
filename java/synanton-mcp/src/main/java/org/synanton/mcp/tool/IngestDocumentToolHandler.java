package org.synanton.mcp.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class IngestDocumentToolHandler implements ToolHandler {
    @Override public String toolName() { return "ingest_document"; }
    @Override public String description() { return "Enqueue an ingest job."; }
    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("uri", Map.of("type", "string")));
    }
    @Override
    public ToolResult invoke(Map<String, Object> arguments, String tenantId) {
        return ToolResult.ok("ingest enqueued for " + arguments.getOrDefault("uri", "") + " tenant=" + tenantId);
    }
}

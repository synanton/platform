package org.synanton.mcp.tool;

/**
 * Discriminated tool outcome (blocker 2d-ii). Handlers report failure
 * explicitly instead of embedding "Error:" text in success content —
 * the endpoint delivers {@code isError} directly.
 */
public record ToolResult(McpContent content, boolean isError) {
    public static ToolResult ok(McpContent content) {
        return new ToolResult(content, false);
    }

    public static ToolResult ok(String text) {
        return ok(new McpContent(text));
    }

    public static ToolResult error(String text) {
        return new ToolResult(new McpContent(text), true);
    }
}

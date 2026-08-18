package org.synanton.mcp.tool;

public record McpContent(String type, String text) {
    public McpContent(String text) { this("text", text); }
}

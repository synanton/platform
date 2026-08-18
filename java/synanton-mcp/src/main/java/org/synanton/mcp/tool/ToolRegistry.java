package org.synanton.mcp.tool;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {
    private final Map<String, ToolHandler> handlers;

    public ToolRegistry(List<ToolHandler> handlerList) {
        this.handlers = new java.util.LinkedHashMap<>();
        for (ToolHandler h : handlerList) {
            handlers.put(h.toolName(), h);
        }
    }

    public Optional<ToolHandler> find(String name) {
        return Optional.ofNullable(handlers.get(name));
    }

    public List<Map<String, Object>> listTools() {
        return handlers.values().stream().map(h -> {
            Map<String, Object> tool = new java.util.LinkedHashMap<>();
            tool.put("name", h.toolName());
            tool.put("description", h.description());
            tool.put("inputSchema", h.inputSchema());
            return tool;
        }).toList();
    }
}

package org.synanton.mcp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.synanton.mcp.tool.McpContent;
import org.synanton.mcp.tool.ScopeEnforcer;
import org.synanton.mcp.tool.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class McpEndpoint {
    private static final Logger log = LoggerFactory.getLogger(McpEndpoint.class);

    private final ToolRegistry toolRegistry;
    private final ScopeEnforcer scopeEnforcer;
    private final ObjectMapper objectMapper;

    public McpEndpoint(ToolRegistry toolRegistry, ScopeEnforcer scopeEnforcer) {
        this.toolRegistry = toolRegistry;
        this.scopeEnforcer = scopeEnforcer;
        this.objectMapper = new ObjectMapper();
    }

    @GetMapping("/mcp")
    public ResponseEntity<Map<String, Object>> capabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("protocolVersion", "2025-03-26");
        caps.put("serverInfo", Map.of("name", "synanton-mcp", "version", "1.0.0"));
        caps.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        caps.put("tools", toolRegistry.listTools());
        return ResponseEntity.ok(caps);
    }

    @PostMapping("/mcp")
    public ResponseEntity<Map<String, Object>> handle(@RequestBody String body, HttpServletRequest request) {
        JsonNode identity = (JsonNode) request.getAttribute("mcpIdentity");
        String authHeader = request.getHeader("Authorization");
        String tenantId = "demo";
        if (identity != null) {
            tenantId = identity.path("tenantId").asText("demo");
        }

        try {
            JsonNode req = objectMapper.readTree(body);
            String id = req.path("id").asText(null);
            String method = req.path("method").asText("");

            return switch (method) {
                case "initialize" -> ResponseEntity.ok(buildResult(id, buildCapabilities()));
                case "tools/list" -> ResponseEntity.ok(buildResult(id, Map.of("tools", toolRegistry.listTools())));
                case "tools/call" -> handleToolCall(id, req.path("params"), tenantId, authHeader, identity);
                default -> ResponseEntity.ok(buildError(id, -32601, "Method not found: " + method));
            };
        } catch (Exception e) {
            log.error("MCP request error", e);
            return ResponseEntity.ok(buildError(null, -32700, "Parse error: " + e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> handleToolCall(
            String id, JsonNode params, String tenantId, String authHeader, JsonNode identity) {
        String toolName = params.path("name").asText("");
        JsonNode argsNode = params.path("arguments");
        Map<String, Object> args = new LinkedHashMap<>();
        if (argsNode.isObject()) {
            argsNode.fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue()));
        }

        var handler = toolRegistry.find(toolName);
        if (handler.isEmpty()) {
            return ResponseEntity.ok(buildResult(id, Map.of("content", List.of(Map.of("type", "text", "text", "Unknown tool: " + toolName)), "isError", true)));
        }
        java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
        if (identity != null && identity.path("scopes").isArray()) {
            identity.path("scopes").forEach(node -> {
                if (node.isTextual()) {
                    scopes.add(node.asText());
                } else if (node.isArray()) {
                    node.forEach(inner -> scopes.add(inner.asText()));
                }
            });
        }
        if (!scopes.isEmpty() && !scopeEnforcer.allowed(toolName, scopes)) {
            return ResponseEntity.ok(buildResult(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", "scope denied: " + scopeEnforcer.requiredScope(toolName))),
                    "isError", true)));
        }

        try {
            McpContent content = handler.get().invoke(args, tenantId, authHeader);
            return ResponseEntity.ok(buildResult(id, Map.of("content", List.of(Map.of("type", content.type(), "text", content.text())), "isError", false)));
        } catch (Exception e) {
            log.error("Tool {} invocation failed", toolName, e);
            return ResponseEntity.ok(buildResult(id, Map.of("content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())), "isError", true)));
        }
    }

    private Map<String, Object> buildCapabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("protocolVersion", "2025-03-26");
        caps.put("serverInfo", Map.of("name", "synanton-mcp", "version", "1.0.0"));
        caps.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        return caps;
    }

    private Map<String, Object> buildResult(String id, Object result) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("result", result);
        return r;
    }

    private Map<String, Object> buildError(String id, int code, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("error", Map.of("code", code, "message", message));
        return r;
    }
}

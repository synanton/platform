package org.synanton.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.mcp.app.McpProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class GraphQueryToolHandler implements ToolHandler {
    private static final Logger log = LoggerFactory.getLogger(GraphQueryToolHandler.class);
    private final String relixUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;

    public GraphQueryToolHandler(McpProperties props) {
        this.relixUrl = props.relixUrl();
        this.timeoutMs = props.toolTimeoutMs();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = new ObjectMapper();
    }

    @Override public String toolName() { return "graph_query"; }
    @Override public String description() {
        return "Query the tenant's knowledge graph. Supports NEIGHBORS, PATH, and COMMUNITY shapes.";
    }
    @Override public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query_shape", Map.of("type", "string", "enum", List.of("NEIGHBORS", "PATH", "COMMUNITY")),
                "params", Map.of("type", "object", "additionalProperties", Map.of("type", "string"))
            ),
            "required", List.of("query_shape", "params")
        );
    }

    @Override
    public McpContent invoke(Map<String, Object> arguments, String tenantId, String authHeader) {
        String queryShape = (String) arguments.getOrDefault("query_shape", "NEIGHBORS");
        Object params = arguments.getOrDefault("params", Map.of());

        try {
            String body = objectMapper.writeValueAsString(Map.of("query_shape", queryShape, "params", params));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(relixUrl + "/graph/query"))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader)
                .header("X-Tenant-Id", tenantId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new McpContent("Error: graph service returned " + response.statusCode());
            }
            JsonNode json = objectMapper.readTree(response.body());
            return new McpContent(formatGraphResult(queryShape, json));
        } catch (Exception e) {
            log.warn("Graph query tool failed for shape '{}': {}", queryShape, e.getMessage());
            return new McpContent("Error: graph service unavailable (relix not yet deployed in Phase 3) - " + e.getMessage());
        }
    }

    private String formatGraphResult(String shape, JsonNode json) {
        StringBuilder sb = new StringBuilder("**Graph Query: ").append(shape).append("**\n\n");
        JsonNode nodes = json.path("nodes");
        if (nodes.isArray()) {
            sb.append("Nodes (N=").append(nodes.size()).append("):\n");
            int count = 0;
            for (JsonNode n : nodes) {
                if (++count > 50) { sb.append("...and ").append(nodes.size() - 50).append(" more\n"); break; }
                sb.append("- ").append(n.path("id").asText()).append(" (").append(n.path("label").asText()).append(")\n");
            }
        }
        JsonNode edges = json.path("edges");
        if (edges.isArray()) {
            sb.append("\nEdges (E=").append(edges.size()).append("):\n");
            int count = 0;
            for (JsonNode e : edges) {
                if (++count > 50) break;
                sb.append("- ").append(e.path("source").asText()).append(" --[").append(e.path("label").asText()).append("]--> ").append(e.path("target").asText()).append("\n");
            }
        }
        return sb.toString();
    }
}

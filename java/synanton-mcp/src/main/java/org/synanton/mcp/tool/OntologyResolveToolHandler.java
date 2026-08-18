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
public class OntologyResolveToolHandler implements ToolHandler {
    private static final Logger log = LoggerFactory.getLogger(OntologyResolveToolHandler.class);
    private final String syntologyUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;

    public OntologyResolveToolHandler(McpProperties props) {
        this.syntologyUrl = props.syntologyUrl();
        this.timeoutMs = props.toolTimeoutMs();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = new ObjectMapper();
    }

    @Override public String toolName() { return "ontology_resolve"; }
    @Override public String description() {
        return "Resolve an entity label to its canonical type, definition, and relations in the tenant's ontology.";
    }
    @Override public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "label", Map.of("type", "string"),
                "version", Map.of("type", "string", "description", "Ontology version (optional, uses pinned or latest).")
            ),
            "required", List.of("label")
        );
    }

    @Override
    public McpContent invoke(Map<String, Object> arguments, String tenantId, String authHeader) {
        String label = (String) arguments.getOrDefault("label", "");
        String version = (String) arguments.getOrDefault("version", "");

        String url = syntologyUrl + "/api/v1/ontology/entities?label=" + java.net.URLEncoder.encode(label, java.nio.charset.StandardCharsets.UTF_8);
        if (!version.isBlank()) url += "&version=" + java.net.URLEncoder.encode(version, java.nio.charset.StandardCharsets.UTF_8);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("X-Tenant-Id", tenantId)
                .GET()
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) return new McpContent("Entity '" + label + "' not found in ontology.");
            if (response.statusCode() != 200) return new McpContent("Error: ontology service returned " + response.statusCode());
            JsonNode json = objectMapper.readTree(response.body());
            return new McpContent(formatOntologyResult(label, json));
        } catch (Exception e) {
            log.warn("Ontology resolve tool failed for label '{}': {}", label, e.getMessage());
            return new McpContent("Error: ontology service unavailable - " + e.getMessage());
        }
    }

    private String formatOntologyResult(String label, JsonNode json) {
        StringBuilder sb = new StringBuilder("**Entity: ").append(label).append("**");
        String type = json.path("type").asText(json.path("entityType").asText(""));
        if (!type.isEmpty()) sb.append(" (type: ").append(type).append(")");
        sb.append("\n\n");
        String def = json.path("definition").asText(json.path("description").asText(""));
        if (!def.isEmpty()) sb.append("Definition: ").append(def).append("\n");
        JsonNode relations = json.path("relations");
        if (relations.isArray() && !relations.isEmpty()) {
            sb.append("\nRelations:\n");
            for (JsonNode r : relations) {
                sb.append("- ").append(r.path("label").asText()).append(" → ").append(r.path("targetType").asText()).append("\n");
            }
        }
        return sb.toString();
    }
}
